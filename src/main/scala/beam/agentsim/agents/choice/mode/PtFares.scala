package beam.agentsim.agents.choice.mode

import java.io.FileNotFoundException
import java.nio.file.{Files, Paths}

import scala.collection.mutable.ListBuffer
import scala.util.Try

import beam.agentsim.agents.choice.mode.PtFares.FareRule
import beam.sim.common.Range
import beam.utils.FileUtils
import org.slf4j.LoggerFactory

case class PtFares(ptFares: List[FareRule]) {

  def getPtFare(agencyId: Option[String], routeId: Option[String], age: Option[Int]): Option[Double] = {
    PtFares
      .filterByAgency(agencyId, PtFares.filterByRoute(routeId, filterByAge(age, ptFares)))
      .map(_.amount)
      .reduceOption(_ + _)
  }

  private def filterByAge(age: Option[Int], ptFares: List[FareRule]) = {

    val ageMatch = ptFares.filter { f =>
      age.fold(false)(f.age.has)
    }

    lazy val ageNoMatch = ptFares.filter { f =>
      age.fold(f.age.isEmpty)(f.age.hasOrEmpty)
    }

    if (ageMatch.isEmpty) ageNoMatch else ageMatch

  }
}

object PtFares {
  private val log = LoggerFactory.getLogger(classOf[PtFares])

  def apply(ptFaresFile: String): PtFares = new PtFares(loadPtFares(ptFaresFile))

  def loadPtFares(ptFaresFile: String): List[FareRule] = {
    if (Files.notExists(Paths.get(ptFaresFile))) {
      log.error("PtFares file not found at location: {}", ptFaresFile)
      throw new FileNotFoundException(s"PtFares file not found at location: $ptFaresFile")
    }
    val fareRules: ListBuffer[FareRule] = ListBuffer()
    val lines = Try(FileUtils.readAllLines(ptFaresFile).toList.tail).getOrElse(List())
    for (line <- lines) {
      val row = line.split(",")

      if (row.length == 4) fareRules += FareRule(row(0), row(1), row(2), row(3))
    }
    fareRules.toList
  }

  case class FareRule(agencyId: String, routeId: String, age: Range, amount: Double)

  object FareRule {

    def apply(agencyId: String, routeId: String, age: String, amount: String): FareRule = new FareRule(
      agencyId,
      routeId,
      Range(age),
      Try(amount.toDouble).getOrElse(0d)
    )
  }

  private def filterByAgency(agencyId: Option[String], ptFares: List[FareRule]): List[FareRule] = {
    val agencyMatch = ptFares.filter { f =>
      agencyId.fold(false)(f.agencyId.equalsIgnoreCase)
    }

    lazy val agencyNotMatch = ptFares.filter { f =>
      agencyId.fold(f.agencyId.isEmpty)(_ => f.agencyId.isEmpty)
    }

    if (agencyMatch.isEmpty) agencyNotMatch else agencyMatch
  }

  private def filterByRoute(routeId: Option[String], ptFares: List[FareRule]): List[FareRule] = {
    val routeMatch = ptFares.filter { f =>
      routeId.fold(false)(f.routeId.equalsIgnoreCase)
    }

    lazy val routeNotMatch = ptFares.filter { f =>
      routeId.fold(f.routeId.isEmpty)(_ => f.routeId.isEmpty)
    }

    if (routeMatch.isEmpty) routeNotMatch else routeMatch
  }

}
