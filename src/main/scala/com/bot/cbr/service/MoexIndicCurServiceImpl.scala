package com.bot.cbr.service

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import cats.data.EitherNec
import cats.effect._
import cats.instances.either._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.parallel._
import com.bot.cbr.algebra.MoexIndicCurService
import com.bot.cbr.config.{Config, MoexCurrencyUrlConfig}
import com.bot.cbr.domain.CBRError.{WrongUrl, WrongXMLFormat}
import com.bot.cbr.domain.date._
import com.bot.cbr.domain.{CBRError, MoexIndicCurrency}
import doobie.util.ExecutionContexts
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.xml.{Elem, Node, XML}

class MoexIndicCurServiceImpl[F[_]: ConcurrentEffect](config: Config, client: Client[F], logger: Logger[F]) extends MoexIndicCurService[F] {

  type E[A] = EitherNec[CBRError, A]

  def url: F[Uri] =
    Uri.fromString(config.urlMoexCurrency).leftMap(p => WrongUrl(p.message): Throwable).liftTo[F]

  override def getCurrencies(exchangeType: String, date: LocalDate): Stream[F, EitherNec[CBRError, MoexIndicCurrency]] = for {
    baseUri <- Stream.eval(url)

    uri = baseUri.withQueryParam("language", "ru")
      .withQueryParam("currency", exchangeType)
      .withQueryParam("moment_start", DateTimeFormatter.ISO_DATE.format(date))
      .withQueryParam("moment_end", DateTimeFormatter.ISO_DATE.format(date))

    _ <- Stream.eval(logger.info(s"getCurrencies MOEX uri: $uri"))
    s <- Stream.eval(client.expect[String](uri))

    ieXml <- Stream.eval(Sync[F].delay(XML.loadString(s))).attempt
    cur <- ieXml match {
      case Right(xml) => parseCurrency(xml)
      case Left(e) =>
        Stream.eval(logger.error(e)(s"Error: ${e.getMessage}")).drain ++
          Stream.emit((WrongXMLFormat(e.getMessage): CBRError).leftNec[MoexIndicCurrency]).covary[F]
    }
  } yield cur

  def parseCurrency(xml: Elem): Stream[F, EitherNec[CBRError, MoexIndicCurrency]] = {
    val eiLst: E[List[Node]] = parseField((xml \ "rates" \ "rate").toList)
    val eiEt: E[String] = parseField(xml \@ "exchange-type")
    (eiEt, eiLst).parMapN {
      case (et, lst) => lst.parTraverse(createMoexCurrency(et))
    }.flatMap(identity).traverse(Stream.emits(_))
  }

  def createMoexCurrency(exchangeType: String)(node: Node): E[MoexIndicCurrency] = {
    val cur: E[BigDecimal] = parseField(BigDecimal(node \@ "value"))
    val dateTime: E[LocalDateTime] = parseField {
      val dateTime = node \@ "moment"
      LocalDateTime.parse(dateTime, dateTimeWhiteSpaceDelimiter)
    }
    (dateTime, cur).parMapN {
      case (dt, c) => MoexIndicCurrency(exchangeType, dt, c)
    }
  }
}


object MoexIndicCurServiceClient extends IOApp {

  def runCurrencyService[F[_]: ConcurrentEffect]: F[Vector[EitherNec[CBRError, MoexIndicCurrency]]] = {

      val currencies = for {
        serverEc <- ExecutionContexts.cachedThreadPool[F]
        client <- BlazeClientBuilder[F](serverEc).resource
        logger <- Resource.liftF(Slf4jLogger.create)
        config = Config("token", "url", "https://www.moex.com/export/derivatives/currency-rate.aspx", "url", MoexCurrencyUrlConfig("url", "url"))
        service = new MoexIndicCurServiceImpl[F](config, client, logger)
      } yield service

      currencies.use(_.getCurrencies("EUR/RUB", LocalDate.now.minusDays(1)).compile.toVector)
  }

  override def run(args: List[String]): IO[ExitCode] =
    runCurrencyService[IO] map println as ExitCode.Success
}