package beam.utils.csv.writers

import scala.collection.JavaConverters._

import beam.utils.scenario.{PersonId, PlanElement}
import ScenarioCsvWriter._
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Leg, Plan, PlanElement => MatsimPlanElement}
import org.matsim.core.population.routes.NetworkRoute

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

  private def getPlanInfo(scenario: Scenario): Iterator[PlanElement] = {
    scenario.getPopulation.getPersons.asScala.iterator.flatMap { case (_, person) =>
      val selectedPlan = person.getSelectedPlan
      person.getPlans.asScala.zipWithIndex.flatMap { case (plan: Plan, planIndex: Int) =>
        val isSelected = selectedPlan == plan
        plan.getPlanElements.asScala.zipWithIndex.map { case (planElement, planElementIndex) =>
          toPlanInfo(
            planIndex = planIndex,
            personId = plan.getPerson.getId.toString,
            planScore = plan.getScore,
            isSelectedPlan = isSelected,
            planElement = planElement,
            planeElementIndex = planElementIndex
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
          legRouteLinks = routeLinks,
          geoId = None
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
          legRouteLinks = Seq.empty,
          geoId = None
        )
    }
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    contentIterator(getPlanInfo(scenario))
  }

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = {
    elements.flatMap {
      case planInfo: PlanElement => Some(toLine(planInfo))
      case _                     => None
    }
  }

  private def toLine(planInfo: PlanElement): String = {
    Seq(
      planInfo.personId.id,
      planInfo.planIndex,
      planInfo.planScore,
      planInfo.planSelected,
      planInfo.planElementType,
      planInfo.planElementIndex,
      planInfo.activityType.getOrElse(""),
      planInfo.activityLocationX.map(_.toString).getOrElse(""),
      planInfo.activityLocationY.map(_.toString).getOrElse(""),
      planInfo.activityEndTime.map(_.toString).getOrElse(""),
      planInfo.legMode.getOrElse(""),
      planInfo.legDepartureTime.getOrElse(""),
      planInfo.legTravelTime.getOrElse(""),
      planInfo.legRouteType.getOrElse(""),
      planInfo.legRouteStartLink.getOrElse(""),
      planInfo.legRouteEndLink.getOrElse(""),
      planInfo.legRouteTravelTime.getOrElse(""),
      planInfo.legRouteDistance.getOrElse(""),
      planInfo.legRouteLinks.mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
    ).mkString("", FieldSeparator, LineSeparator)
  }

}
