package beam.utils.csv.writers

import beam.utils.scenario.{PersonId, PlanElement}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Leg, Plan, PlanElement => MatsimPlanElement}

import scala.collection.JavaConverters._

object PlansCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] = Seq(
    "personId",
    "planIndex",
    "planScore",
    "planSelected",
    "planElementType",
    "planElementIndex",
    "activityType",
    "activityLocationX",
    "activityLocationY",
    "activityEndTime",
    "legMode",
    "legDepartureTime",
    "legTravelTime",
  )

  private case class PlanEntry(
    personId: String,
    planIndex: Int,
    planScore: Double,
    planSelected: Boolean,
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
        planScore,
        planSelected,
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
      case (_, person) =>
        val selectedPlan = person.getSelectedPlan
        person.getPlans.asScala.zipWithIndex.flatMap {
          case (plan: Plan, planIndex: Int) =>
            val isSelected = selectedPlan == plan
            plan.getPlanElements.asScala.zipWithIndex.map {
              case (planElement, planElementIndex) =>
                toPlanInfo(planIndex, plan.getPerson.getId.toString, plan.getScore, isSelected, planElement, planElementIndex)
            }
        }
    }
  }

  private def toPlanInfo(
    planIndex: Int,
    personId: String,
    planScore: Double,
    planIsSelected: Boolean,
    planElement: MatsimPlanElement,
    planeElementIndex: Int
  ): PlanElement = {
    planElement match {
      case leg: Leg =>
        val mode = Option(leg.getMode).flatMap { mode =>
          if (mode == "") None
          else Some(mode)
        }

        PlanElement(
          planIndex = planIndex,
          personId = PersonId(personId),
          planScore = planScore,
          planSelected = planIsSelected,
          planElementType = "leg",
          planElementIndex = planeElementIndex,
          activityType = None,
          activityLocationX = None,
          activityLocationY = None,
          activityEndTime = None,
          legMode = mode
        )
      case act: Activity =>
        PlanElement(
          personId = PersonId(personId),
          planScore = planScore,
          planSelected = planIsSelected,
          planElementType = "activity",
          planElementIndex = planeElementIndex,
          activityType = Option(act.getType),
          activityLocationX = Option(act.getCoord.getX),
          activityLocationY = Option(act.getCoord.getY),
          activityEndTime = Option(act.getEndTime),
          legMode = None
        )
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val plans = getPlanInfo(scenario)
    plans.toIterator.map { planInfo =>
      PlanEntry(
        planIndex = planInfo.planIndex,
        planElementIndex = planInfo.planElementIndex,
        planScore = planInfo.planScore,
        planSelected = planInfo.planSelected,
        personId = planInfo.personId.id,
        planElementType = planInfo.planElementType,
        activityType = planInfo.activityType.getOrElse(""),
        activityLocationX = planInfo.activityLocationX.map(_.toString).getOrElse(""),
        activityLocationY = planInfo.activityLocationY.map(_.toString).getOrElse(""),
        activityEndTime = planInfo.activityEndTime.map(_.toString).getOrElse(""),
        legMode = planInfo.legMode.getOrElse("")
      ).toString
    }
  }

}
