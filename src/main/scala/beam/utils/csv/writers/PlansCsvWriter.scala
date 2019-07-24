package beam.utils.csv.writers

import beam.utils.scenario.{PersonId, PlanElement}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Leg, Plan, PlanElement => MatsimPlanElement}
import org.matsim.core.population.routes.NetworkRoute

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
    "legRouteType",
    "legRouteStartLink",
    "legRouteEndLink",
    "legRouteTravelTime",
    "legRouteDistance",
    "legRouteLinks"
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
    legMode: String,
    legDepartureTime: String,
    legTravelTime: String,
    legRouteType: String,
    legRouteStartLink: String,
    legRouteEndLink: String,
    legRouteTravelTime: Option[Double],
    legRouteDistance: Option[Double],
    legRouteLinks: Seq[String]
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
        legMode,
        legDepartureTime,
        legTravelTime,
        legRouteType,
        legRouteStartLink,
        legRouteEndLink,
        legRouteTravelTime.map(_.toString).getOrElse(""),
        legRouteDistance.map(_.toString).getOrElse(""),
        legRouteLinks.mkString("|")
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
                toPlanInfo(
                  planIndex = planIndex,
                  personId = plan.getPerson.getId.toString,
                  planScore = plan.getScore,
                  isSelectedPlan = isSelected,
                  planElement = planElement,
                  planeElementIndex = planElementIndex,
                )
            }
        }
    }
  }

  private def toPlanInfo(
    planIndex: Int,
    personId: String,
    planScore: Double,
    isSelectedPlan: Boolean,
    planElement: MatsimPlanElement,
    planeElementIndex: Int
  ): PlanElement = {
    planElement match {
      case leg: Leg =>
        // Set legMode to None, if it's empty string
        val mode = Option(leg.getMode).flatMap { mode =>
          if (mode == "") None
          else Some(mode)
        }

        val routeLinks = leg.getRoute match {
          case route: NetworkRoute => route.getLinkIds.asScala.map(_.toString)
          case _                   => Seq.empty
        }

        val route = Option(leg.getRoute)
        PlanElement(
          personId = PersonId(personId),
          planIndex = planIndex,
          planScore = planScore,
          planSelected = isSelectedPlan,
          planElementType = "leg",
          planElementIndex = planeElementIndex,
          activityType = None,
          activityLocationX = None,
          activityLocationY = None,
          activityEndTime = None,
          legMode = mode,
          legDepartureTime = Some(leg.getDepartureTime.toString),
          legTravelTime = Some(leg.getTravelTime.toString),
          legRouteType = route.map(_.getRouteType),
          legRouteStartLink = route.map(_.getStartLinkId.toString),
          legRouteEndLink = route.map(_.getEndLinkId.toString),
          legRouteTravelTime = route.map(_.getTravelTime),
          legRouteDistance = route.map(_.getDistance),
          legRouteLinks = routeLinks
        )
      case act: Activity =>
        PlanElement(
          personId = PersonId(personId),
          planIndex = planIndex,
          planScore = planScore,
          planSelected = isSelectedPlan,
          planElementType = "activity",
          planElementIndex = planeElementIndex,
          activityType = Option(act.getType),
          activityLocationX = Option(act.getCoord.getX),
          activityLocationY = Option(act.getCoord.getY),
          activityEndTime = Option(act.getEndTime),
          legMode = None,
          legDepartureTime = None,
          legTravelTime = None,
          legRouteType = None,
          legRouteStartLink = None,
          legRouteEndLink = None,
          legRouteTravelTime = None,
          legRouteDistance = None,
          legRouteLinks = Seq.empty
        )
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    val plans = getPlanInfo(scenario)
    plans.toIterator.map { planInfo =>
      PlanEntry(
        personId = planInfo.personId.id,
        planIndex = planInfo.planIndex,
        planScore = planInfo.planScore,
        planSelected = planInfo.planSelected,
        planElementType = planInfo.planElementType,
        planElementIndex = planInfo.planElementIndex,
        activityType = planInfo.activityType.getOrElse(""),
        activityLocationX = planInfo.activityLocationX.map(_.toString).getOrElse(""),
        activityLocationY = planInfo.activityLocationY.map(_.toString).getOrElse(""),
        activityEndTime = planInfo.activityEndTime.map(_.toString).getOrElse(""),
        legMode = planInfo.legMode.getOrElse(""),
        legDepartureTime = planInfo.legDepartureTime.getOrElse(""),
        legTravelTime = planInfo.legTravelTime.getOrElse(""),
        legRouteType = planInfo.legRouteType.getOrElse(""),
        legRouteStartLink = planInfo.legRouteStartLink.getOrElse(""),
        legRouteEndLink = planInfo.legRouteEndLink.getOrElse(""),
        legRouteTravelTime = planInfo.legRouteTravelTime,
        legRouteDistance = planInfo.legRouteDistance,
        legRouteLinks = planInfo.legRouteLinks
      ).toString
    }
  }

}
