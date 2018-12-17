package beam.agentsim.agents.choice.mode

import java.io.FileNotFoundException
import java.nio.file.{Files, Paths}

import beam.agentsim.agents.choice.mode.PtFares.FareRule
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

case class PtFares(ptFares: List[FareRule]) {

  def getPtFare(agencyId: Option[String], routeId: Option[String], age: Option[Int]): Option[Double] = {
    val ageFilter = {
      val ageMatch = ptFares
        .filter(
          f =>
            age.fold(false)(f.age.has)
        )

      lazy val ageNoMatch = ptFares
        .filter(
          f =>
            age.fold(f.age.isEmpty)(f.age.hasOrEmpty)
        )

      if(ageMatch.isEmpty) ageNoMatch else ageMatch
    }

  val routeFilter = {
    val routeMatch = ageFilter
      .filter(
        f =>
          routeId.fold(false)(f.routeId.equalsIgnoreCase)
      )

    lazy val routeNotMatch = ageFilter
      .filter(
        f =>
          routeId.fold(f.routeId.isEmpty)(_ => f.routeId.isEmpty)
      )

    if (routeMatch.isEmpty) routeNotMatch else routeMatch
  }

    val agencyFilter = {
      val agencyMatch = routeFilter
        .filter(
          f =>
            agencyId.fold(false)(f.agencyId.equalsIgnoreCase)
        )

      lazy val agencyNotMatch = routeFilter
        .filter(
          f =>
            agencyId.fold(f.agencyId.isEmpty)(_ => f.agencyId.isEmpty)
        )

      if (agencyMatch.isEmpty) agencyNotMatch else agencyMatch
    }

    agencyFilter
      .map(_.amount)
      .reduceOption(_ + _)
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
    val lines = Try(Source.fromFile(ptFaresFile).getLines().toList.tail).getOrElse(List())
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
      Try(amount.toDouble).getOrElse(0D)
    )
  }
}
