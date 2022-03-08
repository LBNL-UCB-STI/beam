package beam.physsim.bprsim

import beam.physsim.bprsim.SimEvent._
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Route}
import org.matsim.api.core.v01.{Id, Scenario, TransportMode}
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup._
import org.matsim.core.mobsim.qsim.agents.ActivityDurationUtils
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle

import scala.language.implicitConversions

/**
  * @author Dmitry Openkov
  */
abstract class SimEvent(
  val time: Double,
  val priority: Int,
  val person: Person,
  val isCACC: Boolean,
  val legIdx: Int,
  val linkIdx: Int
) {
  def previousActivity: Activity = person.getSelectedPlan.getPlanElements.get(legIdx - 1).asInstanceOf[Activity]
  def nextActivity: Activity = person.getSelectedPlan.getPlanElements.get(legIdx + 1).asInstanceOf[Activity]
  val leg: Leg = person.getSelectedPlan.getPlanElements.get(legIdx).asInstanceOf[Leg]
  val isLegStart: Boolean = linkIdx < 0
  val epsilon = 0.001
  val epsilon2 = 0.0001
  def immidiateTime: Double = time + epsilon

  val (linkId, lastLink): (Id[Link], Boolean) =
    leg.getRoute match {
      case route: NetworkRoute =>
        val isLastLink = linkIdx + 1 >= route.getLinkIds.size()
        val lid = if (isLegStart) route.getStartLinkId else route.getLinkIds.get(linkIdx)
        (lid, isLastLink)
      case route: Route =>
        //if the route is not a NetworkRoute then the leg mode is not "car".
        //in this case there wouldn't be real simulation
        //After StartLegSimEvent an EndLegSimEvent is generated.
        val lid = if (isLegStart) route.getStartLinkId else route.getEndLinkId
        val isLastLink = lid == route.getEndLinkId
        (lid, isLastLink)
    }
  def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[SimEvent])
}

object SimEvent {

  private[bprsim] def createVehicleId(person: Person): Id[Vehicle] = {
    Id.create(person.getId, classOf[Vehicle])
  }
}

class StartLegSimEvent(time: Double, priority: Int, person: Person, isCACC: Boolean, legIdx: Int)
    extends SimEvent(time, priority, person, isCACC, legIdx, -1) {

  override def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Some[SimEvent]) = {
    val events = List(
      new ActivityEndEvent(time, person.getId, linkId, previousActivity.getFacilityId, previousActivity.getType),
      new PersonDepartureEvent(time + epsilon2, person.getId, linkId, leg.getMode)
    )

    val simEvent = leg.getMode match {
      case TransportMode.car =>
        //this logic is taken from Matsim org.matsim.core.mobsim.jdeqsim.StartingLegMessage.handleMessage
        // if current leg is in car mode, then enter request in first road
        val emptyLeg = leg.getRoute match {
          case route: NetworkRoute => route.getLinkIds.isEmpty
          case _                   => true
        }
        // if empty leg, then end leg, else simulate leg
        if (emptyLeg) {
          // move to first link in next leg and schedule an end leg message
          // duration of leg = 0 (departure and arrival time is the same)
          new EndLegSimEvent(immidiateTime, PRIORITY_ARRIVAL_MESSAGE, person, isCACC, legIdx, linkIdx)
        } else {
          // car trying to enter traffic
          new EnteringLinkSimEvent(immidiateTime, PRIORITY_ENTER_ROAD_MESSAGE, person, isCACC, legIdx, linkIdx)
        }
      case _ =>
        new EndLegSimEvent(immidiateTime + leg.getTravelTime, PRIORITY_ARRIVAL_MESSAGE, person, isCACC, legIdx, linkIdx)
    }
    (events, Some(simEvent))
  }
}

class EndLegSimEvent(
  time: Double,
  priority: Int,
  person: Person,
  isCACC: Boolean,
  legIdx: Int,
  linkIdx: Int
) extends SimEvent(time, priority, person, isCACC, legIdx, linkIdx) {

  override def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[StartLegSimEvent]) = {
    val nextAct = nextActivity

    val actStartEventTime = Math.max(time, nextAct.getStartTime)
    val activityLinkId = nextAct.getLinkId
    val events = List(
      new VehicleLeavesTrafficEvent(time, person.getId, activityLinkId, createVehicleId(person), leg.getMode, 1.0),
      new PersonArrivalEvent(time + epsilon2, person.getId, activityLinkId, leg.getMode),
      new ActivityStartEvent(
        actStartEventTime + 2 * epsilon2,
        person.getId,
        activityLinkId,
        nextAct.getFacilityId,
        nextAct.getType
      )
    )

    val nextLegExists = person.getSelectedPlan.getPlanElements.size() > legIdx + 2
    val simEvent = if (nextLegExists) {
      val activityDurationInterpretation = scenario.getConfig.plans.getActivityDurationInterpretation
      val departureTime = ActivityDurationUtils.calculateDepartureTime(nextAct, time, activityDurationInterpretation)
      val nextLegStart = Math.max(immidiateTime, departureTime)
      Some(new StartLegSimEvent(nextLegStart, PRIORITY_DEPARTUARE_MESSAGE, person, isCACC, legIdx + 2))
    } else {
      None
    }
    (events, simEvent)
  }
}

class EnteringLinkSimEvent(time: Double, priority: Int, person: Person, isCACC: Boolean, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, priority, person, isCACC, legIdx, linkIdx) {

  override def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[SimEvent]) = {
    //simplification: When a vehicle is entering a road it enters the road immediately
    val vehicleId = createVehicleId(person)
    val event =
      if (isLegStart) new VehicleEntersTrafficEvent(time, person.getId, linkId, vehicleId, null, 1.0)
      else new LinkEnterEvent(time, vehicleId, linkId)
    val events = List(event)
    params.volumeCalculator.vehicleEntered(linkId, time, isCACC)

    val link = scenario.getNetwork.getLinks.get(linkId)
    // calculate time, when the car reaches the end of the link
    val (volume: Double, caccShare: Double) = params.volumeCalculator.getVolumeAndCACCShare(linkId, time)
    val linkTravelTime = params.config.travelTimeFunction(time, link, caccShare, volume)

    (
      events,
      Some(
        new EndLinkSimEvent(
          immidiateTime + linkTravelTime,
          PRIORITY_LEAVE_ROAD_MESSAGE,
          person,
          isCACC,
          legIdx,
          linkIdx
        )
      )
    )
  }
}

class EnteringActivityLinkSimEvent(
  time: Double,
  priority: Int,
  person: Person,
  isCACC: Boolean,
  legIdx: Int,
  linkIdx: Int
) extends SimEvent(time, priority, person, isCACC, legIdx, linkIdx) {

  override def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[SimEvent]) = {
    //simplification: When a vehicle is entering road it enters road immediately
    val vehicleId = createVehicleId(person)
    val events = List(new LinkEnterEvent(time, vehicleId, nextActivity.getLinkId))
    params.volumeCalculator.vehicleEntered(linkId, time, isCACC)

    (events, Some(new EndLegSimEvent(immidiateTime, PRIORITY_ARRIVAL_MESSAGE, person, isCACC, legIdx, linkIdx)))
  }
}

class EndLinkSimEvent(time: Double, priority: Int, person: Person, isCACC: Boolean, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, priority, person, isCACC, legIdx, linkIdx) {

  override def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[SimEvent]) = {
    val vehicleId = createVehicleId(person)
    val events = List(new LinkLeaveEvent(time, vehicleId, linkId))
    val simEvent = if (lastLink) {
      //one needs to enter the next activity link, and then finish the leg
      new EnteringActivityLinkSimEvent(immidiateTime, PRIORITY_ENTER_ROAD_MESSAGE, person, isCACC, legIdx, linkIdx)
    } else {
      new EnteringLinkSimEvent(immidiateTime, PRIORITY_ENTER_ROAD_MESSAGE, person, isCACC, legIdx, linkIdx + 1)
    }
    (events, Some(simEvent))
  }
}
