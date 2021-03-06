package com.bot.cbr.service

import java.time.LocalDate
import java.util.concurrent.Executors

import cats.data.EitherNec
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, IO, Resource}
import com.bot.cbr.{ReadData, UnitSpec}
import com.bot.cbr.utils._
import com.bot.cbr.config.{Config, MoexCurrencyUrlConfig}
import com.bot.cbr.domain.{CBRError, Currency}
import fs2.Stream
import io.chrisdavenport.log4cats.noop.NoOpLogger
import cats.syntax.either._
import doobie.util.ExecutionContexts

import scala.concurrent.ExecutionContext


class CurrencyServiceSpec extends UnitSpec {

  val poolSize = 3

  val expCurrencies = Vector(
    Currency("Австралийский доллар", 1, 48.4192, 36, "AUD").rightNec[CBRError],
    Currency("Азербайджанский манат", 1, 39.4045, 944, "AZN").rightNec[CBRError],
    Currency("Фунт стерлингов Соединенного королевства", 1, 87.0316, 826, "GBP").rightNec[CBRError],
    Currency("Армянский драм", 100, 13.7268, 51, "AMD").rightNec[CBRError],
    Currency("Белорусский рубль", 1, 31.3701, 933, "BYN").rightNec[CBRError],
    Currency("Болгарский лев", 1, 38.7579, 975, "BGN").rightNec[CBRError],
    Currency("Бразильский реал", 1, 17.7768, 986, "BRL").rightNec[CBRError],
    Currency("Венгерский форинт", 100, 23.5885, 348, "HUF").rightNec[CBRError],
    Currency("Гонконгский доллар", 10, 85.3851, 344, "HKD").rightNec[CBRError],
    Currency("Датская крона", 1, 10.1635, 208, "DKK").rightNec[CBRError],
    Currency("Доллар США", 1, 66.8497, 840, "USD").rightNec[CBRError],
    Currency("Евро", 1, 75.8076, 978, "EUR").rightNec[CBRError],
    Currency("Индийская рупия", 100, 91.9623, 356, "INR").rightNec[CBRError],
    Currency("Казахстанский тенге", 100, 17.9544, 398, "KZT").rightNec[CBRError],
    Currency("Канадский доллар", 1, 50.6783, 124, "CAD").rightNec[CBRError],
    Currency("Киргизский сом", 100, 95.9588, 417, "KGS").rightNec[CBRError],
    Currency("Китайский юань", 10, 96.2642, 156, "CNY").rightNec[CBRError],
    Currency("Молдавский лей", 10, 39.2195, 498, "MDL").rightNec[CBRError],
    Currency("Норвежская крона", 10, 79.3327, 578, "NOK").rightNec[CBRError],
    Currency("Польский злотый", 1, 17.6692, 985, "PLN").rightNec[CBRError],
    Currency("Румынский лей", 1, 16.2683, 946, "RON").rightNec[CBRError],
    Currency("СДР (специальные права заимствования)", 1, 92.7881, 960, "XDR").rightNec[CBRError],
    Currency("Сингапурский доллар", 1, 48.5192, 702, "SGD").rightNec[CBRError],
    Currency("Таджикский сомони", 10, 70.9266, 972, "TJS").rightNec[CBRError],
    Currency("Турецкая лира", 1, 12.0949, 949, "TRY").rightNec[CBRError],
    Currency("Новый туркменский манат", 1, 19.1272, 934, "TMT").rightNec[CBRError],
    Currency("Узбекский сум", 10000, 81.0304, 860, "UZS").rightNec[CBRError],
    Currency("Украинская гривна", 10, 23.9390, 980, "UAH").rightNec[CBRError],
    Currency("Чешская крона", 10, 29.2431, 203, "CZK").rightNec[CBRError],
    Currency("Шведская крона", 10, 73.7570, 752, "SEK").rightNec[CBRError],
    Currency("Швейцарский франк", 1, 66.3323, 756, "CHF").rightNec[CBRError],
    Currency("Южноафриканский рэнд", 10, 46.8073, 710, "ZAR").rightNec[CBRError],
    Currency("Вон Республики Корея", 1000, 59.2259, 410, "KRW").rightNec[CBRError],
    Currency("Японская иена", 100, 58.7380, 392, "JPY").rightNec[CBRError]
  )

  val testEc = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
  implicit val cs: ContextShift[IO] = IO.contextShift(testEc)


  "CurrencyService" should "return parsed currency by service url" in {

    runTest[IO]().unsafeRunSync() shouldBe expCurrencies
  }

  def runTest[F[_]: ConcurrentEffect: ContextShift](): F[Vector[EitherNec[CBRError, Currency]]] = {

    val resource = for {
      connEc <- ExecutionContexts.fixedThreadPool[F](poolSize)
      blocker = Blocker.liftExecutionContext(connEc)
      data <- Resource.liftF(new ReadData[F]("src/test/resources/currency_data.xml", blocker).apply())
    } yield data

    resource.use(runCurrencyService[F])
  }

  def runCurrencyService[F[_] : ConcurrentEffect](response: String): F[Vector[EitherNec[CBRError, Currency]]] = {
    val currencies = for {
      client <- Stream.emit(mkClient(response)).covary[F]
      logger <- Stream.emit(NoOpLogger.impl[F]).covary[F]
      config = Config("token", "url", "url", "url", MoexCurrencyUrlConfig("url", "url"))
      service = new CurrencyServiceImpl[F](config, client, logger)
      res <- service.getCurrencies(LocalDate.now())
    } yield res
    currencies.compile.toVector
  }
}