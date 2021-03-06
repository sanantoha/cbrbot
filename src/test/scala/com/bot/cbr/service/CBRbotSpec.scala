package com.bot.cbr.service

import java.time.{LocalDate => LD}
import java.util.concurrent.Executors

import cats.effect.concurrent.Ref
import cats.effect.{Async, ContextShift, IO}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, Apply}
import cats.data.EitherNec
import com.bot.cbr.UnitSpec
import com.bot.cbr.algebra.{BotService, CurrencyService, MetalService, MoexCurrencyService}
import com.bot.cbr.domain.MetalType.{Gold, Palladium, Platinum, Silver}
import com.bot.cbr.domain._
import fs2.Stream
import io.chrisdavenport.log4cats.noop.NoOpLogger
import org.scalatest.BeforeAndAfterEach
import com.bot.cbr.cache.CurrencyCache._
import com.bot.cbr.domain.MoexCurrencyType.{EUR, USD}

import scala.concurrent.ExecutionContext
import scalacache.CatsEffect.modes._


class CBRbotSpec extends UnitSpec with BeforeAndAfterEach {

  val testEc = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
  implicit val cs: ContextShift[IO] = IO.contextShift(testEc)

  val expShowHelpMsg =
    """This bot shows currencies:
      |`?` - show this help message
      |`/currency` usd - show usd currency
      |`/currency` eur 06.11.2018 - show eur currency on the 6th of November in 2018 year
      |`/currency` - show all currencies on today
      |`/currency` all - show all currencies on today
      |`/currency` all 2018-11-06 - show all currencies on the 6th of November in 2018 year
      |`/metal` gold - show gold on today
      |`/metal` all - show all metals on today
      |`/metal` 2018-11-06 2018-11-08 - show all metals on 6, 7 and 8 of November
      |`/moex` usd - show dollar currency on moex exchange on today
      |`/moex` usd 10.01.2019 - show currency on moex exchange on 10.01.2019
      |`/moex` eur - show euro currency on moex exchange on today
      |`/moex` eur 12.01.2019 - show euro currency on moex exchange on 12.01.2019
      |""".stripMargin

  val defLD = LD.of(1970, 1, 1)

  val chatId = 123L

  val metals = List(
    Metal(Gold, LD.of(2018, 12, 1), BigDecimal(2610.66), BigDecimal(2610.66)).rightNec[CBRError],
    Metal(Silver, LD.of(2018, 12, 1), BigDecimal(30.51), BigDecimal(30.51)).rightNec[CBRError],
    Metal(Platinum, LD.of(2018, 12, 1), BigDecimal(1732.67), BigDecimal(1732.67)).rightNec[CBRError],
    Metal(Palladium, LD.of(2018, 12, 1), BigDecimal(2549.81), BigDecimal(2549.81)).rightNec[CBRError],
    Metal(Gold, LD.of(2018, 12, 2), BigDecimal(2611.66), BigDecimal(2612.66)).rightNec[CBRError],
    Metal(Silver, LD.of(2018, 12, 2), BigDecimal(31.51), BigDecimal(32.51)).rightNec[CBRError],
    Metal(Platinum, LD.of(2018, 12, 2), BigDecimal(1733.67), BigDecimal(1734.67)).rightNec[CBRError],
    Metal(Palladium, LD.of(2018, 12, 2), BigDecimal(2550.81), BigDecimal(2551.81)).rightNec[CBRError]
  )

  val moexCurs = List(
    MoexCurrency(USD, LD.of(2019, 1, 15), BigDecimal(66.9875), BigDecimal(-0.0875)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 14), BigDecimal(67.075), BigDecimal(0.2)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 11), BigDecimal(66.875), BigDecimal(-0.035)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 10), BigDecimal(66.91), BigDecimal(0.18)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 9), BigDecimal(66.73), BigDecimal(-0.155)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 8), BigDecimal(66.885), BigDecimal(-0.855)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 4), BigDecimal(67.74), BigDecimal(-0.9)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2019, 1, 3), BigDecimal(68.64), BigDecimal(-1.1775)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2018, 12, 29), BigDecimal(69.8175), BigDecimal(0.3)).rightNec[CBRError],
    MoexCurrency(USD, LD.of(2018, 12, 28), BigDecimal(69.5175), BigDecimal(-0.0425)).rightNec[CBRError]
  )

  override protected def afterEach(): Unit = {
    currencyCache.removeAll[IO]().void.unsafeRunSync()
  }

  "start or ?" should "invoke showHelp method" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "?".some).some)
    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, defLD, expShowHelpMsg))
  }

  it should "invoke showHelp method also" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/start".some).some)
    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, defLD, expShowHelpMsg))
  }

  "Unknown command" should "invoke handleUnknown" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "unknown message".some).some)
    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((-1L, defLD, ""))
  }

  "launch" should "invoke showCurrency for usd on today" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency usd".some).some)

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, LD.now, s"price 1 USD on ${LD.now} is 65"))
  }

  it should "invoke showCurrency for eur on today" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency eur today".some).some)

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, LD.now, s"price 1 EUR on ${LD.now} is 75"))
  }

  it should "invoke showCurrency for usd on yesterday" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency usd yesterday".some).some)

    val expDate = LD.now.minusDays(1)

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, expDate, s"price 1 USD on $expDate is 65"))
  }

  it should "invoke showCurrency for usd on tomorrow" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency usd tomorrow".some).some)

    val expDate = LD.now.plusDays(1)

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, expDate, s"price 1 USD on $expDate is 65"))
  }

  it should "invoke showCurrency for usd on 2018-11-15" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency usd 2018-11-15".some).some)

    val expLD = LD.of(2018, 11, 15)

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, expLD, s"price 1 USD on $expLD is 65"))
  }

  it should "invoke showCurrency for all" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency all".some).some)

    val expLD = LD.now
    val expMsg = s"price 1 USD on $expLD is 65\n" +
      s"price 1 EUR on $expLD is 75\n" +
      s"price 10 CZK on $expLD is 29.53\n"

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, LD.now, expMsg))
  }

  it should "invoke showCurrency for all on 2018-11-15" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency all 2018-11-15".some).some)

    val expLD = LD.of(2018, 11, 15)
    val expMsg = s"price 1 USD on $expLD is 65\n" +
      s"price 1 EUR on $expLD is 75\n" +
      s"price 10 CZK on $expLD is 29.53\n"

    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((chatId, expLD, expMsg))
  }

  it should "invoke showCurrency for bad currency" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/currency bad_currency".some).some)
    runLaunchForCurrency[IO](update).unsafeRunSync() shouldBe ((-1L, LD.now, ""))
  }

  it should "invoke showMetal for gold" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/metal gold 2018-12-01 2018-12-02".some).some)

    val expLD = LD.of(2018, 12, 1)
    val expLocalSecondDate = expLD.plusDays(1)
    val expMsg = s"price Gold on $expLD for buy 2610.66, sell 2610.66\n" +
                 s"price Gold on $expLocalSecondDate for buy 2611.66, sell 2612.66\n"

    runLaunchForMetal[IO](update, metals).unsafeRunSync() shouldBe ((chatId, expLD, expLocalSecondDate, expMsg))
  }

  it should "invoke showMetal for gold on 2018-11-11" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/metal gold 2018-11-11".some).some)

    val expLD = LD.of(2018, 11, 11)

    val expMsg = ""

    runLaunchForMetal[IO](update, Nil).unsafeRunSync() shouldBe ((chatId, expLD, expLD, expMsg))
  }

  it should "invoke showMoexCurrency for USD on 2019-01-15" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/moex usd 2019-01-15".some).some)

    val expMsg = "price USD on 2019-01-15 is 66.9875, change is -0.0875"

    runLaunchForMoexCur[IO](update, moexCurs).unsafeRunSync() shouldBe ((chatId, MoexCurrencyType.USD, expMsg))
  }

  it should "invoke showMoexCurrency for USD on 2019-01-14" in {
    val update = BotUpdate(1L, BotMessage(12L, Chat(chatId), "/moex usd 2019-01-14".some).some)

    val expMsg = "price USD on 2019-01-14 is 67.075, change is 0.2"

    runLaunchForMoexCur[IO](update, moexCurs).unsafeRunSync() shouldBe ((chatId, MoexCurrencyType.USD, expMsg))
  }

  def runLaunchForCurrency[F[_]: Async](botUpdate: BotUpdate): F[(Long, LD, String)] = for {
    chatRef <- Ref.of(-1L)
    ldRef <- Ref.of(defLD)
    dummyRef <- Ref.of(defLD)
    msgRef <- Ref.of("")
    dummyMCRRef <- Ref.of[F, MoexCurrencyType](MoexCurrencyType.USD)

    logger = NoOpLogger.impl[F]
    lcur = List(
      Currency("USD", 1, 65, 840, "USD").rightNec[CBRError],
      Currency("EUR", 1, 75, 978, "EUR").rightNec[CBRError],
      Currency("CZK", 10, 29.53, 203, "CZK").rightNec[CBRError]
    )
    cs = currencyService[F](ldRef, lcur)
    bs = botService[F](chatRef, msgRef, botUpdate.some)
    ms = metalService[F](dummyRef, dummyRef, Nil)
    mc = moexCurrencyService[F](dummyMCRRef, Nil)
    cbtBot = new CBRbot[F](bs, cs, ms, mc, logger)

    _ <- cbtBot.launch.compile.drain

    chatId <- chatRef.get
    ld <- ldRef.get
    msg <- msgRef.get
  } yield (chatId, ld, msg)

  def runLaunchForMetal[F[_]: Async](botUpdate: BotUpdate, metals: List[EitherNec[CBRError, Metal]]): F[(Long, LD, LD, String)] = for {
    chatRef <- Ref.of(-1L)
    startRef <- Ref.of(defLD)
    endRef <- Ref.of(defLD)
    dummyRef <- Ref.of(defLD)
    dummyMCRRef <- Ref.of[F, MoexCurrencyType](MoexCurrencyType.USD)
    msgRef <- Ref.of("")

    logger = NoOpLogger.impl[F]

    cs = currencyService[F](dummyRef, Nil)
    bs = botService[F](chatRef, msgRef, botUpdate.some)
    ms = metalService[F](startRef, endRef, metals)
    mc = moexCurrencyService[F](dummyMCRRef, Nil)
    cbtBot = new CBRbot[F](bs, cs, ms, mc, logger)

    _ <- cbtBot.launch.compile.drain

    chatId <- chatRef.get
    start <- startRef.get
    end <- endRef.get
    msg <- msgRef.get
  } yield (chatId, start, end, msg)

  def runLaunchForMoexCur[F[_]: Async](botUpdate: BotUpdate, moexCurs: List[EitherNec[CBRError, MoexCurrency]]): F[(Long, MoexCurrencyType, String)] = for {
    chatRef <- Ref.of(-1L)
    dummyRef <- Ref.of(defLD)
    msgRef <- Ref.of("")
    moexCurTypeRef <- Ref.of(EUR: MoexCurrencyType)

    logger = NoOpLogger.impl[F]

    cs = currencyService[F](dummyRef, Nil)
    bs = botService(chatRef, msgRef, botUpdate.some)
    ms = metalService[F](dummyRef, dummyRef, Nil)
    mc = moexCurrencyService(moexCurTypeRef, moexCurs)
    cbtBot = new CBRbot[F](bs, cs, ms, mc, logger)

    _ <- cbtBot.launch.compile.drain

    chatId <- chatRef.get
    msg <- msgRef.get
    moexCurType <- moexCurTypeRef.get
  } yield (chatId, moexCurType, msg)

  def botService[F[_]: Apply](chatRef: Ref[F, Long],
                              msgRef: Ref[F, String],
                              botUpdate: Option[BotUpdate]): BotService[F] = new BotService[F] {
    override def sendMessage(chatId: Long, message: String): Stream[F, Unit] = {
      Stream.eval(chatRef.set(chatId) *> msgRef.set(message))
    }

    override def pollUpdates(fromOffset: Long): Stream[F, BotUpdate] =
      botUpdate.map(bu => Stream.emit(bu).covary[F]).getOrElse(Stream.empty)
  }

  def currencyService[F[_]](ldRef: Ref[F, LD], res: List[EitherNec[CBRError, Currency]]): CurrencyService[F] = new CurrencyService[F] {
    override def getCurrencies(date: LD): Stream[F, EitherNec[CBRError, Currency]] =
      Stream.eval(ldRef.set(date)).drain ++ Stream.emits(res).covary[F]
  }

  def metalService[F[_]](startRef: Ref[F, LD], endRef: Ref[F, LD], res: List[EitherNec[CBRError, Metal]]): MetalService[F] = new MetalService[F] {
    override def getMetals(start: LD, end: LD): Stream[F, EitherNec[CBRError, Metal]] =
      Stream.eval(startRef.set(start)).drain ++ Stream.eval(endRef.set(end)).drain ++ Stream.emits(res).covary[F]
  }

  def moexCurrencyService[F[_]: Applicative](moexCurTypeRef: Ref[F, MoexCurrencyType], res: List[EitherNec[CBRError, MoexCurrency]]): MoexCurrencyService[F] = new MoexCurrencyService[F] {
    override def getCurrencies(moexCurType: MoexCurrencyType): Stream[F, EitherNec[CBRError, MoexCurrency]] =
      Stream.eval(moexCurTypeRef.set(moexCurType)).drain ++ Stream.emits(res).covary[F]
  }
}