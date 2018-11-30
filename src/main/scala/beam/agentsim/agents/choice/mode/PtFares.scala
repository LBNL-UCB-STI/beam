package beam.agentsim.agents.choice.mode

import java.io.FileNotFoundException
import java.nio.file.{Files, Paths}

import beam.agentsim.agents.choice.mode.PtFares.FareRule

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

case class PtFares(ptFares: Map[String, List[FareRule]]) {

  def getPtFare(agencyId: String, routeId: Option[String], age: Option[Int]): Option[Double] = {
    ptFares
      .getOrElse(agencyId, List())
      .filter(
        s =>
          s.age.hasOrEmpty(age.getOrElse(0)) &&
          (s.routeId.isEmpty || routeId.fold(false)(s.routeId.equalsIgnoreCase))
      )
      .map(_.amount)
      .reduceOption(_ + _)
  }
}

object PtFares {

  def apply(ptFaresFile: String): PtFares = new PtFares(loadPtFares(ptFaresFile))

  def loadPtFares(ptFaresFile: String): Map[String, List[FareRule]] = {
    if (Files.notExists(Paths.get(ptFaresFile)))
      throw new FileNotFoundException(s"PtFares file not found at location: $ptFaresFile")
    val fareRules: ListBuffer[FareRule] = ListBuffer()
    val lines = Try(Source.fromFile(ptFaresFile).getLines().toList.tail).getOrElse(List())
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
