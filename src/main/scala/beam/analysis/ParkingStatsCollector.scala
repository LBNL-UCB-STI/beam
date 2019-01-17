package beam.analysis
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{LeavingParkingEvent, LeavingParkingEventAttrs, ModeChoiceEvent}
import beam.analysis.plots.{GraphAnalysis, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

/**
  * Collects the inbound and outbound parking overhead times and cost stats.
  * @param beamServices an instance of beam services
  */
class ParkingStatsCollector(beamServices: BeamServices) extends GraphAnalysis with LazyLogging {

  // Store the departed person and his time of departure only when chosen mode is either car or drive_transit.
  private val personDepartures: mutable.LinkedHashMap[Id[Person], Option[Double]] =
    mutable.LinkedHashMap.empty[Id[Person], Option[Double]]
  // Stores parking overhead times grouped by the time bin and parking taz
  private val parkingTimeByBinAndTaz: mutable.LinkedHashMap[(Int, String), List[Double]] =
    mutable.LinkedHashMap.empty[(Int, String), List[Double]]
  private val fileBaseName = "parkingStats"

  /**
    * Creates the required output analysis files at the end of an iteration.
    * @param event an iteration end event.
    */
  override def createGraph(event: IterationEndsEvent): Unit = {
    writeToCsv(event.getIteration, parkingTimeByBinAndTaz)
  }

  /**
    * Processes the collected stats on occurrence of required events.
    * @param event A beam event
    */
  override def processStats(event: Event): Unit = {
    event match {
      //Track the departing person , if he chooses either car or drive_transit mode
      case modeChoiceEvent: ModeChoiceEvent =>
        val modeChoiceEventAttributes = modeChoiceEvent.getAttributes
        val modeChoice: String = modeChoiceEventAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
        modeChoice match {
          case BeamMode.CAR.value | BeamMode.DRIVE_TRANSIT.value =>
            personDepartures.put(modeChoiceEvent.getPersonId, None)
          case _ =>
        }
      // Note the time of the time of departure for the tracked person
      case personDepartureEvent: PersonDepartureEvent =>
        if (personDepartures.contains(personDepartureEvent.getPersonId)) {
          personDepartures.put(personDepartureEvent.getPersonId, Some(personDepartureEvent.getTime))
        }
      // If the person enters a transit vehicle , then stop tracking the person
      //TODO check if the vehicle is a transit type vehicle or not based on vehicle Id
      case personEntersVehicleEvent: PersonEntersVehicleEvent =>
        if (personDepartures.contains(personEntersVehicleEvent.getPersonId) && BeamVehicleType.isTransitVehicle(personEntersVehicleEvent.getVehicleId)) {
          personDepartures.remove(personEntersVehicleEvent.getPersonId)
        }
      // When the tracked person , enter a vehicle and leaves the parking area
      // the time taken from departure to leave parking area is calculates as parking overhead time
      case leavingParkingEvent: LeavingParkingEvent =>
        if (personDepartures.contains(leavingParkingEvent.getPersonId)) {
          personDepartures.getOrElse(leavingParkingEvent.getPersonId, None) match {
            case Some(departureTime) =>
              processParkingStats(leavingParkingEvent, departureTime)
            case None =>
          }
        }
      case _ =>
    }
  }

  /**
    * Processes the computed parking stats from the [[beam.agentsim.events.LeavingParkingEvent]] and the time of person departure
    * @param leavingParkingEvent An instance of [[beam.agentsim.events.LeavingParkingEvent]]
    * @param departureTime time of person departure
    * @return
    */
  private def processParkingStats(leavingParkingEvent: LeavingParkingEvent, departureTime: Double): Unit = {
    try {
      val outboundParkingOverheadTime = leavingParkingEvent.getTime - departureTime
      val leavingParkingEventAttributes = leavingParkingEvent.getAttributes
      val parkingTaz = leavingParkingEventAttributes.get(LeavingParkingEventAttrs.ATTRIBUTE_PARKING_TAZ)
      val hourOfEvent = (leavingParkingEvent.getTime / 3600).toInt
      var parkingTimes = parkingTimeByBinAndTaz.getOrElse(hourOfEvent -> parkingTaz, List.empty)
      parkingTimes = parkingTimes :+ outboundParkingOverheadTime
      parkingTimeByBinAndTaz.put(hourOfEvent -> parkingTaz, parkingTimes)
    } catch {
      case e: Exception => logger.error("Error while processing the parking stats : " + e.getMessage, e)
    }
  }

  /**
    * Write the collected parking stats data to a csv file.
    * @param iterationNumber the current iteration
    * @param parkingTimeByBinAndTaz parking overhead times grouped by the time bin and parking taz
    */
  private def writeToCsv(
                          iterationNumber: Int,
                          parkingTimeByBinAndTaz: mutable.LinkedHashMap[(Int, String), List[Double]]
                        ) = {
    try {
      val header = "timeBin,TAZ,outboundParkingOverheadTime,inboundParkingOverheadTime,inboundParkingOverheadCost"
      val csvFilePath =
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileBaseName + ".csv")
      val inboundParkingOverheadTime = 0
      val inboundParkingOverheadCost = 0
      val data = parkingTimeByBinAndTaz map {
        case ((bin, taz), parkingOverheadTimes) =>
          bin + "," + taz + "," + parkingOverheadTimes.sum / parkingOverheadTimes.size + "," + inboundParkingOverheadTime + "," + inboundParkingOverheadCost
      } mkString "\n"
      FileUtils.writeToFile(csvFilePath, Some(header), data, None)
    } catch {
      case e: Exception => logger.error("Error while writing parking stats data to csv : " + e.getMessage, e)
    }
  }

  /**
    * Handles the post processing steps and resets the state.
    */
  override def resetStats(): Unit = {
    personDepartures.clear()
    parkingTimeByBinAndTaz.clear()
  }

}
