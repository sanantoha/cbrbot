package com.bot.cbr.domain

import java.time.LocalDate

import cats.data.EitherNec
import com.bot.cbr.codec.Decoder
import cats.syntax.either._
import com.bot.cbr.domain.CBRError.WrongCommandInstruction

case class MetalRequest(name: String, startDate: LocalDate, endDate: LocalDate)

object MetalRequest {

  implicit val metalDecoder: Decoder[MetalRequest] = new Decoder[MetalRequest] {
    override def decode(s: String): EitherNec[CBRError, MetalRequest] = {
      s.replaceAll(metalMsg, "").trim.split(" ").map(_.trim) match {
        case Array(m, start, end) => MetalRequest(m, parseDate(start), parseDate(end)).rightNec[CBRError]
        case Array(m, start) =>
          val date = parseDate(start)
          MetalRequest(m, date, date).rightNec[CBRError]
        case Array(msg) if !msg.isEmpty => MetalRequest(msg, LocalDate.now, LocalDate.now).rightNec[CBRError]
        case arr => WrongCommandInstruction(s"Could not parse metal ${arr.mkString(",")}").leftNec[MetalRequest]
      }
    }
  }

}
