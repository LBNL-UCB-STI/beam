package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode

import scala.collection.mutable.ListBuffer
import scala.io.Source

object ModeSubsidy {
  private val subsidies: ListBuffer[Subsidy] = ListBuffer()

  def getSubsidy(mode: BeamMode, age: Option[Double], income: Option[Double]): Double = {
    subsidies.find(s => s.mode == mode && age.fold(true)(s.age.in) && income.fold(true)(s.income.in)).fold(0.0)(_.amount)
  }

  def loadSubsidies(subsidiesFile: String): List[Subsidy] = {
    val lines = Source.fromFile(subsidiesFile).getLines().toList.tail
     for (line <- lines) {
      val row = line.split(",")
       subsidies += Subsidy(BeamMode.fromString(row(0)), Range(row(1)), Range(row(2)), row(3).toDouble)
    }
    subsidies.toList
  }

  case class Subsidy(mode: BeamMode, age: Range, income: Range, amount: Double)

  case class Range(lowerBound: Double, upperBound: Double) {
    def in(value: Double): Boolean = {
      lowerBound < value && value < upperBound
    }
  }

  object Range {
    def apply(pattern: String): Range = {
      val bounds = pattern.split(",")
      val lowerBound = bounds(0).substring(1).toDouble + (if (bounds(0).startsWith("(")) 0 else -1)
      val upperBound = bounds(1).substring(0, bounds(1).length - 1).toDouble + (if (bounds(1).endsWith(")")) 0 else 1)
      Range(lowerBound, upperBound)
    }
  }
}
