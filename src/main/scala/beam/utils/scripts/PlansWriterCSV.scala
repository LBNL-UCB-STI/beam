package beam.utils.scripts

import beam.utils.FileUtils
import beam.utils.scenario.CsvScenarioWriter
import beam.utils.scripts.PlansWriterCSV._
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.internal.MatsimWriter
import org.matsim.core.utils.io.AbstractMatsimWriter

class PlansWriterCSV(
                         scenario: Scenario
                       ) extends AbstractMatsimWriter with StrictLogging
  with MatsimWriter {

  private case class PlanEntry(
                                personId: String,
                                planId: Int,
                                planElementType: String,
                                activityIndex: Int,
                                activityType: String,
                                locationX: String,
                                locationY: String,
                                endTime: String,
                                mode: String
                              ) {
    override def toString: String = {
      Seq(personId, planId, planElementType, activityIndex, activityType, locationX, locationY, endTime, mode)
        .mkString("", ColumnSeparator, LineTerminator)
    }
  }

  override def write(filename: String): Unit = {
    val plans = CsvScenarioWriter.getPlanInfo(scenario)
    val allEntries = plans.map { planInfo =>
      PlanEntry(
        planId = planInfo.planElementIndex,
        activityIndex = planInfo.planElementIndex,  //TODO: what is the right value?
        personId = planInfo.personId.id,
        planElementType = planInfo.planElement,
        activityType = planInfo.activityType.getOrElse(""),
        locationX = planInfo.x.map(_.toString).getOrElse(""),
        locationY = planInfo.y.map(_.toString).getOrElse(""),
        endTime= planInfo.endTime.map(_.toString).getOrElse(""),
        mode = planInfo.mode.getOrElse("")
      )
    }

    val header = Iterator(Columns.mkString("", ColumnSeparator, LineTerminator))
    val contentIterator = allEntries.toIterator.map(_.toString)

    FileUtils.writeToFile(filename, header ++ contentIterator)
  }

}

object PlansWriterCSV{
  private val Columns = Seq("personId","planId","planElementType","activityIndex","activityType","locationX","locationY","endTime","mode")
  private val ColumnSeparator = ","
  private val LineTerminator = "\n"

  def apply(scenario: Scenario): PlansWriterCSV = {
    new PlansWriterCSV(scenario)
  }
}