package beam.agentsim.agents.rideHail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try


case class RideHailStatsEntry(sumOfRequestedRides: Double, sumOfWaitingtimes: Double, sumOfIdlingVehicles: Double)

/*
class RideHailStats(mutable.Map[String, ArrayBuffer[RideHailStatsEntry]]){
  def getRideHailingStats(coord: Coord, time: Double): RideHailStatsEntry = {
  }
}*/

class TNCWaitingTimesCollector(eventsManager: EventsManager, beamConfig: BeamConfig) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption
  //    mTazTreeMap.get.getTAZ(1,1).tazId


  // timeBins -> number OfTimeBins input
  val rideHaillingConfig = beamConfig.beam.agentsim.agents.rideHailing
  val timeBinSize = rideHaillingConfig.surgePricing.timeBinSize
  val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSize).toInt + 1

  //
  val SECONDS_IN_HOUR = 3600

  val ridesEvents: mutable.Map[String, ModeChoiceEvent] = mutable.Map();

  val rides: mutable.Map[Int, Double] = mutable.Map()
  val waitingTimes: mutable.Map[Int, Double] = mutable.Map()
  val personModeChoiceEvents: mutable.Map[Id[Person], Event] = mutable.Map()

  val rideHailStats = mutable.Map[String, ArrayBuffer[RideHailStatsEntry]]()

  //



  def getEventHour(time: Double): Int = {
    (time / SECONDS_IN_HOUR).toInt
  }

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

  /*
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
  }*/

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {
      case PersonEntersVehicleEvent.EVENT_TYPE => {

        //collectWaitingTimes(event)
      }
      case ModeChoiceEvent.EVENT_TYPE => {
        //collectWaitingTimes(event)
        collectRides(event.asInstanceOf[ModeChoiceEvent])
      }
      case PathTraversalEvent.EVENT_TYPE => {

        calculateRideStats(event.asInstanceOf[PathTraversalEvent])
      }
      case _ =>
    }
  }

  def collectWaitingTimes(event: Event): Unit ={
    event.getEventType match {
      case ModeChoiceEvent.EVENT_TYPE => {

        val modeChoiceEvent: ModeChoiceEvent = event.asInstanceOf[ModeChoiceEvent]
        val personId: Id[Person] = modeChoiceEvent.getPersonId
        personModeChoiceEvents.put(personId, event)
      }
      case PersonEntersVehicleEvent.EVENT_TYPE => {

        val personEntersVehicleEvent: PersonEntersVehicleEvent = event.asInstanceOf[PersonEntersVehicleEvent]
        val personId: Id[Person] = personEntersVehicleEvent.getPersonId

        personModeChoiceEvents.get(personId) match {
          case Some(e) => {
            val startTime = e.getTime
            val endTime = personEntersVehicleEvent.getTime
            val waitingTime = endTime - startTime

            val hour = getEventHour(startTime)

            waitingTimes.get(hour) match {
              case Some(wt) => {
                val wt2 = wt + waitingTime
                waitingTimes.put(hour, wt2)
              }
              case None => {
                waitingTimes.put(hour, waitingTime)
              }
            }
          }
          case None =>
        }
      }
      case _ =>
    }
  }

  /*
    Calculating sumOfRides

    collectRides
    1. This method will collect all the ModeChoice events where the mode is 'rideHailing'
    2. Afterwards when a PathTraversal event occurs for the same vehicle with num_passengers = 1 we will find the tazid
      using coords from the PathTraversal event
   */
  def collectRides(event: ModeChoiceEvent): Unit = {

    val mode = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE)

    if(mode.equals("ride_hailing")) {

      val personId = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      ridesEvents.put(personId, event)
    }
  }

  /*
    Calculating sumOfRides
    calculateRidesStats
   */
  def calculateRideStats(event: PathTraversalEvent): Unit = {

    val mode = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
    val vehicleId = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)

    if(mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")){
      // This means its the rideHailing PathTraversal

      val personId: String = vehicleId.substring(vehicleId.lastIndexOf("="))

      ridesEvents.get(personId) match{
        case Some(modeChoiceEvent) => {

          val startX = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble
          val startY = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble

          //
          val binIndex = getEventHour(modeChoiceEvent.getTime)
          val tazId = getTazId(startX, startY)

          rideHailStats.get(tazId) match {
            case Some(entries: ArrayBuffer[RideHailStatsEntry]) => {
              val entry = entries(binIndex)

              if(entry == null){
                entries(binIndex) = RideHailStatsEntry(1, 0, 0)
              }else{
                val _entry = entry.copy(entry.sumOfRequestedRides + 1)
                entries(binIndex) = _entry
              }
            }
          }

          ridesEvents.remove(personId)
        }
        case None =>
      }


    }
  }

  def getTazId(startX: Double, startY: Double): String = {
    "1"
  }
}