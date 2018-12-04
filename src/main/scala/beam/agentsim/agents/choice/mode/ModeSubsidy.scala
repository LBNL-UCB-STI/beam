package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.mode.ModeSubsidy.Subsidy
import beam.router.Modes.BeamMode

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

class ModeSubsidy(private val subsidiesFile: String) {
  private val modeSubsidies: Map[BeamMode, List[Subsidy]] = loadSubsidies(subsidiesFile)

  def getSubsidy(mode: BeamMode, age: Option[Int], income: Option[Int]): Double = {
    modeSubsidies
      .getOrElse(mode, List())
      .filter(
        s =>
          (age.fold(true)(s.age.hasOrEmpty) && income.fold(false)(s.income.hasOrEmpty)) ||
          (age.fold(false)(s.age.hasOrEmpty) && income.fold(true)(s.income.hasOrEmpty))
      )
      .map(_.amount)
      .sum
  }

  private def loadSubsidies(subsidiesFile: String): Map[BeamMode, List[Subsidy]] = {
    val subsidies: ListBuffer[Subsidy] = ListBuffer()
    val lines = Try(Source.fromFile(subsidiesFile).getLines().toList.tail).getOrElse(List())
    for (line <- lines) {
      val row = line.split(",")

      if (row.length == 4) subsidies += Subsidy(row(0), row(1), row(2), row(3))
    }
    subsidies.toList.groupBy(_.mode)
  }
}

object ModeSubsidy {
  case class Subsidy(mode: BeamMode, age: Range, income: Range, amount: Double)

  object Subsidy {

    def apply(mode: String, age: String, income: String, amount: String): Subsidy = new Subsidy(
      BeamMode.fromString(mode),
      Range(age),
      Range(income),
      Try(amount.toDouble).getOrElse(0D)
    )
  }

  case class Range(lowerBound: Int, upperBound: Int) {
    val isEmpty = false

    def has(value: Int): Boolean = {
      lowerBound <= value && value <= upperBound
    }

    def hasOrEmpty(value: Int): Boolean = {
      isEmpty || has(value)
    }
  }

  object Range {

    def apply(pattern: String): Range = {
      if (pattern == null || pattern.isEmpty) Range.empty()
      else {
        val bounds = pattern.split(":")
        val lowerBound = Try(
          bounds(0).substring(1).toInt
          + (if (bounds(0).startsWith("(")) 1 else 0)
        ).getOrElse(0)
        val upperBound = Try(
          bounds(1).substring(0, bounds(1).length - 1).toInt
          - (if (bounds(1).endsWith(")")) 1 else 0)
        ).getOrElse(Int.MaxValue)
        Range(lowerBound, upperBound)
      }
    }

    def apply(lowerBound: Int, upperBound: Int): Range = {
      if (lowerBound == 0 && upperBound == 0)
        Range.empty()
      else
        new Range(lowerBound, upperBound)
    }

    def empty(): Range = new Range(0, 0) {
      override val isEmpty = true
    }

    def test(): Unit = {
      assert(Range(0, 0).isEmpty)
      assert(Range("[:]") == Range(0, 2147483647))
      assert(Range("[0:]") == Range(0, 2147483647))
      assert(Range("[:2147483647]") == Range(0, 2147483647))
      assert(Range("[0:2147483647]") == Range(0, 2147483647))
      assert(Range("[1:10]") == Range(1, 10))
      assert(Range("(1:10]") == Range(2, 10))
      assert(Range("[1:10)") == Range(1, 9))

      val ms = new ModeSubsidy("test/input/beamville/subsidies.csv")
      assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(5), Some(30000)) == 4)
      assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(25), Some(30000)) == 3)
      assert(ms.getSubsidy(BeamMode.RIDE_HAIL, None, None) == 0)
    }
  }

  def main(args: Array[String]): Unit = {
    Range.test()
  }
}
