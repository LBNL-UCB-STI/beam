package beam.agentsim.agents.choice.mode

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Paths}

import scala.collection.mutable.ListBuffer
import scala.util.Try

import beam.agentsim.agents.choice.mode.ModeIncentive.Incentive
import beam.router.Modes.BeamMode
import beam.sim.common._
import beam.sim.population.AttributesOfIndividual
import beam.utils.FileUtils

case class ModeIncentive(modeIncentives: Map[BeamMode, List[Incentive]]) {

  def computeIncentive(attributesOfIndividual: AttributesOfIndividual, mode: BeamMode): Double = {
    val incentive: Double =
      // incentive for non-public transport
      getIncentive(
        mode,
        attributesOfIndividual.age,
        attributesOfIndividual.income.map(x => x.toInt)
      ).getOrElse(0)

    incentive
  }

  def getIncentive(mode: BeamMode, age: Option[Int], income: Option[Int]): Option[Double] = {
    modeIncentives
      .getOrElse(mode, List())
      .filter(s => age.fold(false)(s.age.hasOrEmpty) && income.fold(true)(s.income.hasOrEmpty))
      .map(_.amount)
      .reduceOption(_ + _)
  }

}

object ModeIncentive {

  def apply(modeIncentivesFile: String): ModeIncentive = new ModeIncentive(loadIncentives(modeIncentivesFile))

  def loadIncentives(incentivesFile: String): Map[BeamMode, List[Incentive]] = {
    if (Files.notExists(Paths.get(incentivesFile)))
      throw new FileNotFoundException(s"ModeIncentive file not found at location: $incentivesFile")
    val incentives: ListBuffer[Incentive] = ListBuffer()
    val lines = Try(FileUtils.readAllLines(incentivesFile).toList.tail).getOrElse(List())
    for (line <- lines) {
      val row = line.split(",")

      if (row.length == 4) incentives += Incentive(row(0), row(1), row(2), row(3))
    }
    incentives.toList.groupBy(_.mode)
  }

  case class Incentive(mode: BeamMode, age: Range, income: Range, amount: Double)

  object Incentive {

    def apply(mode: String, age: String, income: String, amount: String): Incentive = new Incentive(
      BeamMode.fromString(mode).get,
      Range(age),
      Range(income),
      Try(amount.toDouble).getOrElse(0d)
    )
  }
}
