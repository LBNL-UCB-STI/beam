package beam.utils.csv.writers

import scala.collection.JavaConverters._
import beam.utils.scenario.{PersonId, PlanElement}
import ScenarioCsvWriter._
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Leg, Plan, PlanElement => MatsimPlanElement}
import org.matsim.core.population.routes.NetworkRoute
import beam.utils.OptionalUtils.OptionalTimeExtension
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

object PlansCsvWriter extends ScenarioCsvWriter {

  override protected val fields: Seq[String] = Seq(
    "tripId",
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
    "trip_dur_min",
    "trip_cost_dollars",
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
          tripId = Option(leg.getAttributes.getAttribute("trip_id"))
            .map(_.toString.filter(x => x.isDigit || x.equals('.')))
            .getOrElse(""),
          personId = PersonId(personId),
          planIndex = planIndex,
          planScore = planScore,
          planSelected = isSelectedPlan,
          planElementType = PlanElement.Leg,
          planElementIndex = planeElementIndex,
          activityType = None,
          activityLocationX = None,
          activityLocationY = None,
          activityEndTime = None,
          legMode = mode,
          legDepartureTime = leg.getDepartureTime.toOption.map(_.toString),
          legTravelTime = leg.getTravelTime.toOption.map(_.toString),
          legExpectedTravelTime =
            Option(leg.getAttributes.getAttribute("trip_dur_min")).map(_.toString).filterNot(_.isEmpty).map(_.toDouble),
          legExpectedCost = Option(leg.getAttributes.getAttribute("trip_cost_dollars"))
            .map(_.toString)
            .filterNot(_.isEmpty)
            .map(_.toDouble),
          legRouteType = route.map(_.getRouteType),
          legRouteStartLink = route.map(_.getStartLinkId.toString),
          legRouteEndLink = route.map(_.getEndLinkId.toString),
          legRouteTravelTime = route.map(_.getTravelTime.orElse(beam.UNDEFINED_TIME)),
          legRouteDistance = route.map(_.getDistance),
          legRouteLinks = routeLinks,
          geoId = None
        )
      case act: Activity =>
        PlanElement(
          tripId = "",
          personId = PersonId(personId),
          planIndex = planIndex,
          planScore = planScore,
          planSelected = isSelectedPlan,
          planElementType = PlanElement.Activity,
          planElementIndex = planeElementIndex,
          activityType = Option(act.getType),
          activityLocationX = Option(act.getCoord.getX),
          activityLocationY = Option(act.getCoord.getY),
          activityEndTime = act.getEndTime.toOption,
          legMode = None,
          legDepartureTime = None,
          legTravelTime = None,
          legExpectedTravelTime = None,
          legExpectedCost = None,
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
      planInfo.tripId,
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
      planInfo.legExpectedTravelTime.getOrElse(""),
      planInfo.legExpectedCost.getOrElse(""),
      planInfo.legTravelTime.getOrElse(""),
      planInfo.legRouteType.getOrElse(""),
      planInfo.legRouteStartLink.getOrElse(""),
      planInfo.legRouteEndLink.getOrElse(""),
      planInfo.legRouteTravelTime.getOrElse(""),
      planInfo.legRouteDistance.getOrElse(""),
      planInfo.legRouteLinks.mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
    ).mkString("", FieldSeparator, LineSeparator)
  }

  def plansOutputDataDescriptor(iterationLevel: Boolean): OutputDataDescriptor = {
    val simStep = if (iterationLevel) "iteration" else "simulation"
    OutputDataDescriptorObject("PlansCsvWriter", s"plans.csv.gz", iterationLevel)(
      s"""
      tripId              | Empty column. This table contains person plan elements at the beginning of $simStep.
      personId            | Person id
      planIndex           | Plan index
      planScore           | Plan score
      planSelected        | Boolean value indicating if the plan is selected
      planElementType     | Activity or Leg
      planElementIndex    | Index of the plan element
      activityType        | Activity type (Work, Home, Shopping etc)
      activityLocationX   | X part of activity location coordinate
      activityLocationY   | Y part of activity location coordinate
      activityEndTime     | Activity end time
      legMode             | Leg mode
      legDepartureTime    | Leg departure time
      legTravelTime       | Leg travel time
      legRouteType        | Identifier describing the type of this leg route uniquely.
      legRouteStartLink   | Leg route start link id
      legRouteEndLink     | Leg route end link id
      legRouteTravelTime  | Leg route travel time (equals to leg travel time)
      legRouteDistance    | Leg route distance
      legRouteLinks       | List of leg route link ids
      """
    )
  }
}
