package beam.agentsim.agents.choice.mode

import java.io.File

import beam.agentsim.agents.choice.mode.ModeSubsidy.Subsidy
import beam.router.Modes.BeamMode
import beam.sim.population.AttributesOfIndividual

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

case class ModeSubsidy(modeSubsidies: Map[BeamMode, List[Subsidy]]) {

  def computeSubsidy(attributesOfIndividual: AttributesOfIndividual, mode: BeamMode): Double = {
    val subsidy: Double =
      // subsidy for non-public transport
      getSubsidy(
        mode,
        attributesOfIndividual.age,
        attributesOfIndividual.income.map(x => x.toInt)
      ).getOrElse(0)

    subsidy
  }

  def getSubsidy(mode: BeamMode, age: Option[Int], income: Option[Int]): Option[Double] = {
    modeSubsidies
      .getOrElse(mode, List())
      .filter(s =>
        age.fold(false)(s.age.hasOrEmpty) && income.fold(true)(s.income.hasOrEmpty)
      )
    .map(_.amount).reduceOption(_ + _)
  }

}

object ModeSubsidy {

  def loadSubsidies(subsidiesFile: String): Map[BeamMode, List[Subsidy]] = {
    val subsidies: ListBuffer[Subsidy] = ListBuffer()
    val lines = Try(Source.fromFile(new File(subsidiesFile).toString).getLines().toList.tail).getOrElse(List())
    for (line <- lines) {
      val row = line.split(",")

      if (row.length == 4) subsidies += Subsidy(row(0), row(1), row(2), row(3))
    }
    subsidies.toList.groupBy(_.mode)
  }

  case class Subsidy(mode: BeamMode, age: Range, income: Range, amount: Double)

  object Subsidy {

    def apply(mode: String, age: String, income: String, amount: String): Subsidy = new Subsidy(
      BeamMode.fromString(mode),
      Range(age),
      Range(income),
      Try(amount.toDouble).getOrElse(0D)
    )
  }
}
