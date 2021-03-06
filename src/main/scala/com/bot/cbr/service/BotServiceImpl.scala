package com.bot.cbr.service

import cats.ApplicativeError
import cats.effect._
import cats.instances.list._
import cats.instances.long._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.functor._
import com.bot.cbr.algebra.BotService
import com.bot.cbr.config.{Config, MoexCurrencyUrlConfig}
import com.bot.cbr.domain.CBRError.WrongUrl
import com.bot.cbr.domain.{BotResponse, BotUpdate}
import doobie.util.ExecutionContexts
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.auto._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.{EntityDecoder, Uri}


class BotServiceImpl[F[_]: ConcurrentEffect](config: Config, client: Client[F], logger: Logger[F]) extends BotService[F] {

  private val botApiUri = Uri.fromString(config.urlBotapi).leftMap(p => WrongUrl(p.message))

  implicit val decoder: EntityDecoder[F, BotResponse[List[BotUpdate]]] = jsonOf[F, BotResponse[List[BotUpdate]]]

  def sendMessageUri(chatId: Long, message: String): Stream[F, Uri] = {
    Stream.eval(ApplicativeError[F, Throwable].fromEither(
      botApiUri.map { uri =>
        uri / "sendMessage" =? Map (
          "chat_id" -> List(chatId.toString),
          "parse_mode" -> List("Markdown"),
          "text" -> List(message))
      }
    ))
  }

  def pollUpdatesUri(offset: Long): Stream[F, Uri] = {
    Stream.eval(ApplicativeError[F, Throwable].fromEither(
      botApiUri.map { uri =>
        uri / "getUpdates" =? Map(
          "offset" -> List((offset + 1).toString),
          "timeout" -> List("0.5"),
          "allowed_updates" -> List("""["message"]""")
        )
      }
    ))
  }

  def lastOffset(botResponse: BotResponse[List[BotUpdate]]): Option[Long] =
    botResponse.result.map(_.update_id).maximumOption

  def requestUpdates(fromOffset: Long): Stream[F, (Long, BotResponse[List[BotUpdate]])] = for {
    uri <- pollUpdatesUri(fromOffset)
    _ <- Stream.eval(logger.debug(s"pollUpdates uri: ${uri.toString}"))

    eiBotResponse <- Stream.eval(client.expect[BotResponse[List[BotUpdate]]](uri)).attempt

    res <- eiBotResponse match {
      case Left(e) =>
        Stream.eval(logger.error(e)("Failed to poll updates")).drain ++
        Stream.emit(fromOffset -> BotResponse(ok = true, List.empty[BotUpdate])).covary[F]
      case Right(br) => Stream.emit(lastOffset(br).getOrElse(fromOffset) -> br).covary[F]
    }
    _ <- Stream.eval(logger.debug(s"Response: ${res.toString}"))
  } yield res


  override def sendMessage(chatId: Long, message: String): Stream[F, Unit] = for {

    _ <- Stream.eval(logger.info(s"invoke sendMessage($chatId, $message)"))

    msg <- if (message.trim.isEmpty) Stream.empty
           else Stream.emit(message).covary[F]

    uri <- sendMessageUri(chatId, msg)
    _ <- Stream.eval(logger.debug(s"sendMessage uri: ${uri.toString}"))
    res <- Stream.eval(client.expect[Unit](uri)).attempt
    _ <- res match {
      case Right(_) =>
        Stream.eval(logger.info(s"Message was sent successfully to chat id: $chatId. Message=$msg"))
      case Left(e) =>
        Stream.eval(logger.error(e)(s"Message was sent with error to chat id: $chatId. Message=$msg"))
    }
  } yield ()

  override def pollUpdates(fromOffset: Long): Stream[F, BotUpdate] = {
    Stream(()).repeat.covary[F]
        .evalMapAccumulate(fromOffset) {
          case (offset, _) =>
            requestUpdates(offset).compile.
              toList.map(_.headOption.getOrElse(offset -> BotResponse(ok = true, List.empty[BotUpdate])))
        }.flatMap {
          case (_, response) => Stream.emits(response.result)
        }
  }

}

object BotAPIServiceTest extends IOApp {

  import cats.syntax.show._
  import com.bot.cbr.domain.BotUpdate._

  def runSendMessage[F[_]: ConcurrentEffect: Timer]: F[ExitCode] = {
      val res = for {
        serverEc <- ExecutionContexts.cachedThreadPool[F]
        client <- BlazeClientBuilder[F](serverEc).resource
        logger <- Resource.liftF(Slf4jLogger.create)

        config = Config("https://api.telegram.org/bot<Token>", "url", "url", "url", MoexCurrencyUrlConfig("url", "url"))

        service = new BotServiceImpl[F](config, client, logger)
      } yield service

      res.use { service =>
        (service.sendMessage(-311412191, "test!").drain ++
          service.sendMessage(-311412191, "Hello world!").drain).compile.drain
      }.as(ExitCode.Success)
    }

  def runPollUpdates[F[_]: ConcurrentEffect]: F[ExitCode] = {
        val res = for {
          serverEc <- ExecutionContexts.cachedThreadPool[F]
          client <- BlazeClientBuilder[F](serverEc).resource
          logger <- Resource.liftF(Slf4jLogger.create)

          config = Config("https://api.telegram.org/bot<Token>", "url", "url", "url", MoexCurrencyUrlConfig("url", "url"))
          service = new BotServiceImpl[F](config, client, logger)

        } yield service

        res.use { service =>
          val r = for {
          logger <- Stream.eval(Slf4jLogger.create)
            botUpdate <- service.pollUpdates(1)
            _ <- Stream.eval(logger.info(botUpdate.show))
          } yield ()
          r.compile.drain
        }.as(ExitCode.Success)
    }

    override def run(args: List[String]): IO[ExitCode] = {
      runSendMessage[IO]
//      runPollUpdates[IO]
    }

}