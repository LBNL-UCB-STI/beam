package beam.physsim.bprsim

import beam.physsim.bprsim.SimEvent._
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Id, Scenario, TransportMode}
import org.matsim.core.mobsim.qsim.agents.ActivityDurationUtils
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.vehicles.Vehicle

/**
  *
  * @author Dmitry Openkov
  */
abstract class SimEvent(val time: Double, val person: Person, val legIdx: Int, val linkIdx: Int) {
  def previousActivity: Activity = person.getSelectedPlan.getPlanElements.get(legIdx - 1).asInstanceOf[Activity]
  def nextActivity: Activity = person.getSelectedPlan.getPlanElements.get(legIdx + 1).asInstanceOf[Activity]
  val leg = person.getSelectedPlan.getPlanElements.get(legIdx).asInstanceOf[Leg]
  val isLegStart = linkIdx < 0

  val (linkId, lastLink): (Id[Link], Boolean) =
    leg.getRoute match {
      case route: NetworkRoute =>
        val isLastLink = linkIdx + 1 >= route.getLinkIds.size()
        val lid = if (isLegStart) route.getStartLinkId else route.getLinkIds.get(linkIdx)
        (lid, isLastLink)
      case _ => throw new RuntimeException(s"Only network route supported $leg")
    }
  def execute(scenario: Scenario, params: BPRSimParams): (List[Event], Option[SimEvent])
}

object SimEvent {

  private[bprsim] def createVehicleId(person: Person) = {
    Id.create(person.getId, classOf[Vehicle])
  }
}

class StartLegSimEvent(time: Double, person: Person, legIdx: Int) extends SimEvent(time, person, legIdx, -1) {
  override def execute(scenario: Scenario, params: BPRSimParams) = {
    val events = List(
      new ActivityEndEvent(time, person.getId, linkId, previousActivity.getFacilityId, previousActivity.getType),
      new PersonDepartureEvent(time, person.getId, linkId, leg.getMode),
    )

    val simEvent = leg.getMode match {
      case TransportMode.car =>
        // if current leg is in car mode, then enter request in first road
        val emptyLeg = leg.getRoute match {
          case route: NetworkRoute => route.getLinkIds.isEmpty
          case _                   => true
        }
        // if empty leg, then end leg, else simulate leg
        if (emptyLeg) {
          // move to first link in next leg and schedule an end leg message
          // duration of leg = 0 (departure and arrival time is the same)
          new EndLegSimEvent(time, person, legIdx, linkIdx)
        } else {
          // car trying to enter traffic
          new EnteringLinkSimEvent(time, person, legIdx, linkIdx)
        }
      case _ =>
        new EndLegSimEvent(time + leg.getTravelTime, person, legIdx, linkIdx)
    }
    (events, Some(simEvent))
  }
}

class EndLegSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {
  override def execute(scenario: Scenario, params: BPRSimParams) = {
    val nextAct = nextActivity

    val actStartEventTime = Math.max(time, nextAct.getStartTime)
    val activityLinkId = nextAct.getLinkId
    val events = List(
      new VehicleLeavesTrafficEvent(time, person.getId, activityLinkId, createVehicleId(person), leg.getMode, 1.0),
      new PersonArrivalEvent(time, person.getId, activityLinkId, leg.getMode),
      new ActivityStartEvent(actStartEventTime, person.getId, activityLinkId, nextAct.getFacilityId, nextAct.getType),
    )
    params.volumeCalculator.addVolume(linkId, -1)

    val nextLegExists = person.getSelectedPlan.getPlanElements.size() > legIdx + 2
    val simEvent = if (nextLegExists) {
      val activityDurationInterpretation = scenario.getConfig.plans.getActivityDurationInterpretation
      val departureTime = ActivityDurationUtils.calculateDepartureTime(nextAct, time, activityDurationInterpretation)
      val nextLegStart = Math.max(time, departureTime)
      Some(new StartLegSimEvent(nextLegStart, person, legIdx + 2))
    } else {
      None
    }
    (events, simEvent)
  }
}

class EnteringLinkSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {
  override def execute(scenario: Scenario, params: BPRSimParams) = {
    //simplification: When a vehicle is entering road it enters road immediately
    val vehicleId = createVehicleId(person)
    val event =
      if (isLegStart) new VehicleEntersTrafficEvent(time, person.getId, linkId, vehicleId, null, 1.0)
      else new LinkEnterEvent(time, vehicleId, linkId)
    val events = List(event)
    params.volumeCalculator.addVolume(linkId, 1)

    val link = scenario.getNetwork.getLinks.get(linkId)
    // calculate time, when the car reaches the end of the link
    val volume: Int = params.volumeCalculator.getVolume(linkId)
    val linkTravelTime = params.config.travelTime(time, link, volume)

    (events, Some(new EndLinkSimEvent(time + linkTravelTime, person, legIdx, linkIdx)))
  }
}

class EnteringActivityLinkSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {
  override def execute(scenario: Scenario, params: BPRSimParams) = {
    //simplification: When a vehicle is entering road it enters road immediately
    val vehicleId = createVehicleId(person)
    val events = List(new LinkEnterEvent(time, vehicleId, nextActivity.getLinkId))

    (events, Some(new EndLegSimEvent(time, person, legIdx, linkIdx)))
  }
}

class EndLinkSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {

  override def execute(scenario: Scenario, params: BPRSimParams) = {
    val vehicleId = createVehicleId(person)
    val events = List(new LinkLeaveEvent(time, vehicleId, linkId))
    params.volumeCalculator.addVolume(linkId, -1)
    val simEvent = if (lastLink) {
      //one needs to enter the next activity link, and then finish the leg
      new EnteringActivityLinkSimEvent(time, person, legIdx, linkIdx)
    } else {
      new EnteringLinkSimEvent(time, person, legIdx, linkIdx + 1)
    }
    (events, Some(simEvent))
  }
}
