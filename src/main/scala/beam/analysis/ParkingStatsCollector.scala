package beam.analysis
import java.util

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{LeavingParkingEvent, LeavingParkingEventAttrs, ModeChoiceEvent}
import beam.analysis.plots.{GraphAnalysis, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode
import beam.sim.{BeamServices, OutputDataDescription}
import beam.utils.{FileUtils, OutputDataDescriptor}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps

/**
  * Collects the inbound and outbound parking overhead times and cost stats.
  * @param beamServices an instance of beam services
  */
class ParkingStatsCollector(beamServices: BeamServices) extends GraphAnalysis with LazyLogging {

  // Store the departed person and his time of departure only when chosen mode is either car or drive_transit.
  private val personDepartures: mutable.LinkedHashMap[Id[Person], Option[Double]] =
    mutable.LinkedHashMap.empty[Id[Person], Option[Double]]
  // Stores parking overhead times grouped by the time bin and parking taz
  private val parkingStatsByBinAndTaz: mutable.LinkedHashMap[(Int, String), ParkingStatsCollector.ParkingStats] =
    mutable.LinkedHashMap.empty[(Int, String), ParkingStatsCollector.ParkingStats]
  private val fileBaseName = "parkingStats"

  /**
    * Creates the required output analysis files at the end of an iteration.
    * @param event an iteration end event.
    */
  override def createGraph(event: IterationEndsEvent): Unit = {
    writeToCsv(event.getIteration, parkingStatsByBinAndTaz)
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
      case personEntersVehicleEvent: PersonEntersVehicleEvent =>
        if (personDepartures.contains(personEntersVehicleEvent.getPersonId) && BeamVehicleType.isTransitVehicle(
              personEntersVehicleEvent.getVehicleId
            )) {
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
      val parkingStats = parkingStatsByBinAndTaz.getOrElse(
        hourOfEvent -> parkingTaz,
        ParkingStatsCollector.ParkingStats(List.empty, List.empty, List.empty)
      )
      val outboundParkingTimes = parkingStats.outboundParkingTimeOverhead :+ outboundParkingOverheadTime
      parkingStatsByBinAndTaz.put(
        hourOfEvent -> parkingTaz,
        parkingStats.copy(outboundParkingTimeOverhead = outboundParkingTimes)
      )
    } catch {
      case e: Exception => logger.error("Error while processing the parking stats : " + e.getMessage, e)
    }
  }

  /**
    * Write the collected parking stats data to a csv file.
    * @param iterationNumber the current iteration
    * @param parkingStatsByBinAndTaz parking overhead times grouped by the time bin and parking taz
    */
  private def writeToCsv(
    iterationNumber: Int,
    parkingStatsByBinAndTaz: mutable.LinkedHashMap[(Int, String), ParkingStatsCollector.ParkingStats]
  ) = {
    try {
      val header = "timeBin,TAZ,outboundParkingOverheadTime,inboundParkingOverheadTime,inboundParkingOverheadCost"
      val csvFilePath =
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileBaseName + ".csv")
      val inboundParkingOverheadTime = 0
      val inboundParkingOverheadCost = 0
      val data = parkingStatsByBinAndTaz map {
        case ((bin, taz), parkingStats) =>
          bin + "," + taz + "," +
          parkingStats.outboundParkingTimeOverhead.sum / parkingStats.outboundParkingTimeOverhead.size + "," +
          inboundParkingOverheadTime + "," +
          inboundParkingOverheadCost
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
    parkingStatsByBinAndTaz.clear()
  }

}

object ParkingStatsCollector extends OutputDataDescriptor {

  case class ParkingStats(
    outboundParkingTimeOverhead: List[Double],
    inboundParkingTimeOverhead: List[Double],
    inboundParkingCostOverhead: List[Double]
  )

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: util.List[OutputDataDescription] = {

    val outputFileBaseName = "parkingStats"
    val filePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, outputFileBaseName + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath: String = filePath.replace(outputDirPath, "")
    val outputDataDescription =
      OutputDataDescription(classOf[ParkingStatsCollector].getSimpleName.dropRight(1), relativePath, "", "")
    List(
      "timeBin"                     -> "Bin in a day",
      "TAZ"                         -> "Central point of the parking location",
      "outboundParkingOverheadTime" -> "Time taken by the person to depart , park vehicle and leave the parking area",
      "inboundParkingOverheadTime"  -> "Time taken by the person to walk from the parked car to the destination",
      "inboundParkingOverheadCost"  -> "Vehicle parking cost"
    ) map {
      case (header, description) =>
        outputDataDescription.copy(field = header, description = description)
    } asJava
  }
}
