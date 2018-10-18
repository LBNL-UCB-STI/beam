package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.mode.ModeSubsidy.{Subsidy, Range}
import beam.router.Modes.BeamMode

import scala.io.Source

class ModeSubsidy(val subsidiesFile: String) {
  private val modeSubsidies: Map[BeamMode, List[Subsidy]] = loadSubsidies(subsidiesFile)

  def getSubsidy(mode: BeamMode, age: Option[Int], income: Option[Int]): Double = {
    modeSubsidies(mode).find(s => age.fold(true)(s.age.in) && income.fold(true)(s.income.in)).fold(0.0)(_.amount)
  }

  private def loadSubsidies(subsidiesFile: String): Map[BeamMode, List[Subsidy]] = {
    var subsidies: Map[BeamMode, List[Subsidy]] = Map()
    val lines = Source.fromFile(subsidiesFile).getLines().toList.tail
    for (line <- lines) {
      val row = line.split(",")

      val beamMode = BeamMode.fromString(row(0))
      val subsidy = Subsidy(beamMode, Range(row(1)), Range(row(2)), row(3).toDouble)

      subsidies += beamMode -> (subsidies.getOrElse(beamMode, List()) :+ subsidy)
    }
    subsidies
  }
}

object ModeSubsidy {
  case class Subsidy(mode: BeamMode, age: Range, income: Range, amount: Double)

  case class Range(lowerBound: Int, upperBound: Int) {
    val isEmpty = false
    def in(value: Int): Boolean = {
      isEmpty || (lowerBound < value && value < upperBound)
    }
  }

  object Range {
    def apply(pattern: String): Range = {
      if(pattern == null || pattern.isEmpty) return Range.empty()
      val bounds = pattern.split(":")
      val lowerBound = bounds(0).substring(1).toInt + (if (bounds(0).startsWith("(")) 0 else -1)
      val upperBound = bounds(1).substring(0, bounds(1).length - 1).toInt + (if (bounds(1).endsWith(")")) 0 else 1)
      Range(lowerBound, upperBound)
    }

    def empty(): Range = new Range(0,0) {
      override val isEmpty = true
    }
  }
}
