package beam.physsim.bprsim

import beam.physsim.bprsim.SimEvent._
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.api.core.v01.{Id, Scenario, TransportMode}
import org.matsim.core.api.experimental.events.EventsManager
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
  def firstLink = linkIdx == 0
  val (linkId, lastLink): (Id[Link], Boolean) =
      leg.getRoute match {
        case route: NetworkRoute =>
          val lastLink = linkIdx >= route.getLinkIds.size()
          val lid = if (lastLink) route.getEndLinkId else route.getLinkIds.get(linkIdx)
          (lid, lastLink)
        case _                   => throw new RuntimeException(s"Only network route supported $leg")
      }
  def execute(implicit eventManager: EventsManager, scenario: Scenario): Option[SimEvent]
}

object SimEvent {
  private[bprsim] def generateEvents(events: Event*)(implicit eventManager: EventsManager): Unit = {
    events.foreach(eventManager.processEvent)
  }

  private[bprsim] def createVehicleId(person: Person) = {
    Id.create(person.getId, classOf[Vehicle])
  }
}

class StartLegSimEvent(time: Double, person: Person, legIdx: Int)
    extends SimEvent(time, person, legIdx, 0) {
  override def execute(implicit eventManager: EventsManager, scenario: Scenario) = {
    generateEvents(
      new ActivityEndEvent(time, person.getId, linkId, previousActivity.getFacilityId, previousActivity.getType),
      new PersonDepartureEvent(time, person.getId, linkId, leg.getMode),
    )

    leg.getMode match {
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
          Some(new EndLegSimEvent(time, person, legIdx, linkIdx))
        } else {
          // car trying to enter traffic
//          road.enterRequest(vehicle, getMessageArrivalTime)
          Some(new EnteringLinkSimEvent(time, person, legIdx, linkIdx))
        }
      case _ =>
        Some(new EndLegSimEvent(time + leg.getTravelTime, person, legIdx, linkIdx))
    }

  }
}

class EndLegSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {
  override def execute(implicit eventManager: EventsManager, scenario: Scenario) = {
    val nextAct = nextActivity

    val actStartEventTime = Math.max(time, nextAct.getStartTime)
    generateEvents(
      new VehicleLeavesTrafficEvent(time, person.getId, linkId, createVehicleId(person), leg.getMode, 1.0),
      new PersonArrivalEvent(time, person.getId, linkId, leg.getMode),
      new ActivityStartEvent(actStartEventTime, person.getId, linkId, nextAct.getFacilityId, nextAct.getType),
    )

    val nextLegExists = person.getSelectedPlan.getPlanElements.size() > legIdx + 2
    if (nextLegExists) {
      val activityDurationInterpretation = scenario.getConfig.plans.getActivityDurationInterpretation
      val departureTime = ActivityDurationUtils.calculateDepartureTime(nextAct, time, activityDurationInterpretation)
      val nextLegStart = Math.max(time, departureTime)
      Some(new StartLegSimEvent(nextLegStart, person, legIdx + 2))
    } else {
      None
    }
  }
}

class EnteringLinkSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {
  override def execute(implicit eventManager: EventsManager, scenario: Scenario) = {
    //simplification: When a vehicle is entering road it enters road immediately
    val vehicleId = createVehicleId(person)
    val event =
      if (firstLink) new VehicleEntersTrafficEvent(time, person.getId, linkId, vehicleId, null, 1.0)
      else new LinkEnterEvent(time, vehicleId, linkId)
    generateEvents(event)

    val link = scenario.getNetwork.getLinks.get(linkId)
    // calculate time, when the car reaches the end of the link
    val linkTravelTime = link.getLength / link.getFreespeed(time)

    Some(new EndLinkSimEvent(time + linkTravelTime, person, legIdx, linkIdx))
  }
}

class EndLinkSimEvent(time: Double, person: Person, legIdx: Int, linkIdx: Int)
    extends SimEvent(time, person, legIdx, linkIdx) {

  override def execute(implicit eventManager: EventsManager, scenario: Scenario) = {
    val vehicleId = createVehicleId(person)
    generateEvents(new LinkLeaveEvent(time, vehicleId, linkId))
    if (lastLink) {
      Some(new EndLegSimEvent(time, person, legIdx, linkIdx))
    } else {
      Some(new EnteringLinkSimEvent(time, person, legIdx, linkIdx + 1))
    }
  }
}
