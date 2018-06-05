package beam.agentsim.agents.rideHail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time

import scala.collection.mutable
import scala.util.Try

class TNCWaitingTimesCollector(eventsManager: EventsManager, beamConfig: BeamConfig) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption
  //    mTazTreeMap.get.getTAZ(1,1).tazId


  // timeBins -> number OfTimeBins input
  val rideHaillingConfig = beamConfig.beam.agentsim.agents.rideHailing
  val timeBinSize = rideHaillingConfig.surgePricing.timeBinSize
  val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSize).toInt + 1


  //numberOfRides: -> passengers =1 (sum of rides)
  //customerWaitTime -> sum and average

  //idleTimes = count in each bin according to how much time remaining
  // agent arrives in a time 1000 and leaves at time 2000
  // bin Size=100 -> count as idle in 10 bins (from 1000 to 2000)
  //idleTime[TAZId,binNumber] // bin 10, 11, 12,...19 we do +1


  eventsManager.addHandler(this)

  def getTNCIdlingTimes(): Set[WaitingEvent] = {
    ???
  }

  def getTNCPassengerWaitingTimes(): Set[WaitingEvent] = {
    ???
  }

  def tellHistoryToRideHailIterationHistoryActor(): Unit = {
    // TODO: send message to actor with collected data
  }
val eventTypes = mutable.Set[String]()
  val rideHailingWaiting = new mutable.HashMap[String, ModeChoiceEvent]()
  override def handleEvent(event: Event): Unit = {
    eventTypes.add(event.getEventType)

    if (event.isInstanceOf[ModeChoiceEvent]) {

      val mode = event.getAttributes().get("mode");
      if (mode.equalsIgnoreCase("ride_hailing")) {
        val modeChoiceEvent = event.asInstanceOf[ModeChoiceEvent]

        val pId = modeChoiceEvent.getPersonId.toString
        rideHailingWaiting.put(pId, modeChoiceEvent)
      }
    } else if (event.isInstanceOf[PathTraversalEvent]) {
      val pathTraversalEvent = event.asInstanceOf[PathTraversalEvent]

      val numPass = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)
      if (numPass.equals("1")) {
        val vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);

      }
    } else if (event.isInstanceOf[PersonEntersVehicleEvent]) {

      val personEntersVehicleEvent = event.asInstanceOf[PersonEntersVehicleEvent];
      val personId = personEntersVehicleEvent.getPersonId().toString

      if (rideHailingWaiting.contains(personId)) {
        val modeChoiceEvent = rideHailingWaiting.get(personId).get
        val waiting = personEntersVehicleEvent.getTime() - modeChoiceEvent.getTime()
        //TODO: utilize waiting time
      }
    }
  }
}