package beam.agentsim.agents.choice.mode

import java.io.File

import beam.agentsim.agents.choice.mode.ModeSubsidy.Subsidy
import beam.router.Modes.BeamMode

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

case class ModeSubsidy(modeSubsidies: Map[BeamMode, List[Subsidy]]) {

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

  def main(args: Array[String]): Unit = {
    test()
  }

  def test(): Unit = {
    val ms = new ModeSubsidy(loadSubsidies("test/input/beamville/subsidies.csv"))
    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(5), Some(30000)) == 4)
    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(25), Some(30000)) == 3)
    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, None, None) == 0)
  }

}
