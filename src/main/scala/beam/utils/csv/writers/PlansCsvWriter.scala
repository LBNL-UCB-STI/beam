package beam.utils.csv.writers

import beam.utils.scenario.CsvScenarioWriter
import org.matsim.api.core.v01.Scenario

object PlansCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] = Seq("personId", "planId", "planElementType", "activityIndex",
    "activityType", "locationX", "locationY", "endTime", "mode")

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
        .mkString("", FieldSeparator, LineSeparator)
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val plans = CsvScenarioWriter.getPlanInfo(scenario)
    plans.toIterator.map { planInfo =>
      PlanEntry(
        planId = planInfo.planElementIndex,
        activityIndex = planInfo.planElementIndex, //TODO: what is the right value?
        personId = planInfo.personId.id,
        planElementType = planInfo.planElement,
        activityType = planInfo.activityType.getOrElse(""),
        locationX = planInfo.x.map(_.toString).getOrElse(""),
        locationY = planInfo.y.map(_.toString).getOrElse(""),
        endTime = planInfo.endTime.map(_.toString).getOrElse(""),
        mode = planInfo.mode.getOrElse("")
      ).toString
    }
  }


}
