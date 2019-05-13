package beam.utils.csv.writers

import beam.utils.scenario.{PersonId, PlanElement}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Leg, PlanElement => MatsimPlanElement}

import scala.collection.JavaConverters._

object PlansCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] = Seq(
    "personId",
    "planIndex",
    "planElementType",
    "planElementIndex",
    "activityType",
    "activityLocationX",
    "activityLocationY",
    "activityEndTime",
    "legMode"
  )

  private case class PlanEntry(
    personId: String,
    planIndex: Int,
    planElementType: String,
    planElementIndex: Int,
    activityType: String,
    activityLocationX: String,
    activityLocationY: String,
    activityEndTime: String,
    legMode: String
  ) {
    override def toString: String = {
      Seq(
        personId,
        planIndex,
        planElementType,
        planElementIndex,
        activityType,
        activityLocationX,
        activityLocationY,
        activityEndTime,
        legMode
      ).mkString("", FieldSeparator, LineSeparator)
    }
  }

  private def getPlanInfo(scenario: Scenario): Iterable[PlanElement] = {
    scenario.getPopulation.getPersons.asScala.flatMap {
      case (id, person) =>
        // We get only selected plan!
        Option(person.getSelectedPlan).map { plan =>
          plan.getPlanElements.asScala.zipWithIndex.map {
            case (planElement, index) =>
              toPlanInfo(plan.getPerson.getId.toString, planElement, index)
          }
        }
    }.flatten
  }

  private def toPlanInfo(personId: String, planElement: MatsimPlanElement, index: Int): PlanElement = {
    planElement match {
      case leg: Leg =>
        // Set legMode to None, if it's empty string
        val mode = Option(leg.getMode).flatMap { mode =>
          if (mode == "") None
          else Some(mode)
        }

        PlanElement(
          personId = PersonId(personId),
          planElement = "leg",
          planElementIndex = index,
          activityType = None,
          x = None,
          y = None,
          endTime = None,
          mode = mode
        )
      case act: Activity =>
        PlanElement(
          personId = PersonId(personId),
          planElement = "activity",
          planElementIndex = index,
          activityType = Option(act.getType),
          x = Option(act.getCoord.getX),
          y = Option(act.getCoord.getY),
          endTime = Option(act.getEndTime),
          mode = None
        )
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val plans = getPlanInfo(scenario)
    plans.toIterator.map { planInfo =>
      PlanEntry(
        planIndex = planInfo.planElementIndex,
        planElementIndex = planInfo.planElementIndex,
        personId = planInfo.personId.id,
        planElementType = planInfo.planElement,
        activityType = planInfo.activityType.getOrElse(""),
        activityLocationX = planInfo.x.map(_.toString).getOrElse(""),
        activityLocationY = planInfo.y.map(_.toString).getOrElse(""),
        activityEndTime = planInfo.endTime.map(_.toString).getOrElse(""),
        legMode = planInfo.mode.getOrElse("")
      ).toString
    }
  }

}
