package beam.agentsim.agents.choice.mode

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Paths}

import beam.agentsim.agents.choice.mode.PtFares.FareRule
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

case class PtFares(ptFares: Map[String, List[FareRule]]) {

  def getPtFare(agencyId: String, routeId: Option[String], age: Option[Int]): Option[Double] = {
    ptFares
      .getOrElse(agencyId, List())
      .filter(s => age.fold(true)(s.age.hasOrEmpty) && routeId.fold(true)(s.routeId.equalsIgnoreCase))
      .map(_.amount)
      .reduceOption(_ + _)
  }
}

object PtFares extends LazyLogging {

  def apply(ptFaresFile: String): PtFares = new PtFares(loadPtFares(ptFaresFile))

  def loadPtFares(subsidiesFile: String): Map[String, List[FareRule]] = {
    if (Files.notExists(Paths.get(subsidiesFile)))
      throw new FileNotFoundException(s"PtFares file not found at location: $subsidiesFile")
    val fareRules: ListBuffer[FareRule] = ListBuffer()
    val lines = Try(Source.fromFile(subsidiesFile).getLines().toList.tail).getOrElse(List())
    for (line <- lines) {
      val row = line.split(",")

      if (row.length == 4) fareRules += FareRule(row(0), row(1), row(2), row(3))
    }
    fareRules.toList.groupBy(_.agencyId)
  }

  case class FareRule(agencyId: String, routeId: String, age: Range, amount: Double)

  object FareRule {

    def apply(agencyId: String, routeId: String, age: String, amount: String): FareRule = new FareRule(
      agencyId,
      routeId,
      Range(age),
      Try(amount.toDouble).getOrElse(0D)
    )
  }
}
