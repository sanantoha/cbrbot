package com.bot.cbr.algebra

import java.time.LocalDate

import cats.data.EitherNec
import com.bot.cbr.domain.{CBRError, Currency}
import fs2.Stream

trait CurrencyService[F[_]] {

  def getCurrencies(date: LocalDate): Stream[F, EitherNec[CBRError, Currency]]
}
