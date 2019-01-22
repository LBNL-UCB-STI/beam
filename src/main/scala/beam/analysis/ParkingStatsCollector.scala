package beam.analysis
import java.util

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events._
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

  // Stores the person and his parking related stats (time of departure + parking time + parking cost + parkingTAZ)
  // only when the mode of choice is either car or drive_transit.
  private val personParkingStatsTracker: mutable.LinkedHashMap[Id[Person], ParkingStatsCollector.PersonParkingStats] =
    mutable.LinkedHashMap.empty[Id[Person], ParkingStatsCollector.PersonParkingStats]

  // Stores parking stats grouped by the time bin and parking taz
  private val parkingStatsByBinAndTaz: mutable.LinkedHashMap[(Int, String), ParkingStatsCollector.ParkingStats] =
    mutable.LinkedHashMap.empty[(Int, String), ParkingStatsCollector.ParkingStats]

  // Base name of the file that stores the output of the parking stats
  private val fileBaseName = "parkingStats"

  /**
    * Creates the required output analysis files at the end of an iteration.
    * @param event an iteration end event.
    */
  override def createGraph(event: IterationEndsEvent): Unit = {
    //write the parking stats collected by time bin and parking TAZ to a csv file
    writeToCsv(event.getIteration, parkingStatsByBinAndTaz)
  }

  /**
    * Processes the collected stats on occurrence of the required events.
    * @param event A beam event
    */
  override def processStats(event: Event): Unit = {

    event match {

      /*
             If the occurred event is a ModeChoiceEvent and when the mode of choice is either car or drive_transit
             start tracking the departing person
       */
      case modeChoiceEvent: ModeChoiceEvent =>
        val modeChoiceEventAttributes = modeChoiceEvent.getAttributes
        val modeChoice = Option(modeChoiceEventAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)).getOrElse("")
        modeChoice match {
          case BeamMode.CAR.value | BeamMode.DRIVE_TRANSIT.value =>
            // start tracking the person
            if (!personParkingStatsTracker.contains(modeChoiceEvent.getPersonId)) {
              personParkingStatsTracker.put(
                modeChoiceEvent.getPersonId,
                ParkingStatsCollector.PersonParkingStats(None, None, None, None)
              )
            }
          case _ =>
        }

      /*
             If the occurred event is a PersonDepartureEvent and if the person is being tracked
             store the time of departure of the person.
       */
      case personDepartureEvent: PersonDepartureEvent =>
        if (personParkingStatsTracker.contains(personDepartureEvent.getPersonId)) {
          val personParkingStats = personParkingStatsTracker.getOrElse(
            personDepartureEvent.getPersonId,
            ParkingStatsCollector.PersonParkingStats(None, None, None, None)
          )
          //store the departure time of the person
          personParkingStatsTracker.put(
            personDepartureEvent.getPersonId,
            personParkingStats.copy(departureTime = Some(personDepartureEvent.getTime))
          )
        }

      /*
             If the occurred event is a PersonEntersVehicleEvent and if that vehicle is a transit vehicle
             stop tracking the person
       */
      case personEntersVehicleEvent: PersonEntersVehicleEvent =>
        if (personParkingStatsTracker.contains(personEntersVehicleEvent.getPersonId) && BeamVehicleType
              .isTransitVehicle(
                personEntersVehicleEvent.getVehicleId
              )) {
          //stop tracking the person
          personParkingStatsTracker.remove(personEntersVehicleEvent.getPersonId)
        }

      /*
             If the occurred event is a ParkEvent and if the person is being tracked
             store the parking time and parking cost
       */
      case parkEvent: ParkEvent =>
        if (personParkingStatsTracker.contains(parkEvent.getPersonId)) {
          val personParkingStats = personParkingStatsTracker.getOrElse(
            parkEvent.getPersonId,
            ParkingStatsCollector.PersonParkingStats(None, None, None, None)
          )
          val parkingCost: Option[Double] = try {
            Option(parkEvent.getAttributes.get(ParkEventAttrs.ATTRIBUTE_COST)).map(_.toDouble)
          } catch {
            case e: Exception =>
              logger.error("Error while reading cost attribute and converting it to double : " + e.getMessage, e)
              None
          }
          //store the parking time + parking cost + parking TAZ for the person
          personParkingStatsTracker.put(
            parkEvent.getPersonId,
            personParkingStats.copy(
              parkingTime = Some(parkEvent.getTime),
              parkingCost = parkingCost,
              parkingTAZId = Option(parkEvent.getAttributes.get(ParkEventAttrs.ATTRIBUTE_PARKING_TAZ))
            )
          )
        }

      /*
             If the occurred event is a LeavingParkingEvent and if the person is being tracked
             process the parking stats collected so far for that person
       */
      case leavingParkingEvent: LeavingParkingEvent =>
        if (personParkingStatsTracker.contains(leavingParkingEvent.getPersonId)) {
          val personParkingStats = personParkingStatsTracker.getOrElse(
            leavingParkingEvent.getPersonId,
            ParkingStatsCollector.PersonParkingStats(None, None, None, None)
          )
          processParkingStats(leavingParkingEvent, personParkingStats)
        }

      /*
             If the occurred event is a PathTraversalEvent and if the person is being tracked is the vehicle driver
             process the parking stats collected so far for that person
       */
      case pathTraversalEvent: PathTraversalEvent =>
        val pathTraversalEventAttributes = pathTraversalEvent.getAttributes
        val driverId: Option[String] = Option(pathTraversalEventAttributes.get(PathTraversalEvent.ATTRIBUTE_DRIVER_ID))
        driverId match {
          case Some(dId) =>
            if (personParkingStatsTracker.contains(Id.createPersonId(dId))) {
              val personParkingStats = personParkingStatsTracker.getOrElse(
                Id.createPersonId(dId),
                ParkingStatsCollector.PersonParkingStats(None, None, None, None)
              )
              processParkingStats(pathTraversalEvent, personParkingStats)
            }
          case None =>
            logger.error(s"No driver id attribute defined for the PathTraversalEvent")
        }

      case _ =>
    }
  }

  /**
    * Processes the computed parking stats from the [[beam.agentsim.events.LeavingParkingEvent]] and the time of person departure
    * @param event instance of an event
    * @param personParkingStats PersonParkingStats of a person
    */
  private def processParkingStats(event: Event, personParkingStats: ParkingStatsCollector.PersonParkingStats): Unit = {
    try {

      event match {

        case _ if event.isInstanceOf[LeavingParkingEvent] =>
          val leavingParkingEvent = event.asInstanceOf[LeavingParkingEvent]
          if (personParkingStats.departureTime.isDefined) {
            // Calculate the outbound parking overhead time
            val outboundParkingTime = leavingParkingEvent.getTime - personParkingStats.departureTime.get
            // Compute the hour of event occurrence
            val hourOfEvent = (leavingParkingEvent.getTime / 3600).toInt
            // Get the parking TAZ from the event
            val leavingParkingEventAttributes = leavingParkingEvent.getAttributes
            val parkingTaz = Option(leavingParkingEventAttributes.get(LeavingParkingEventAttrs.ATTRIBUTE_PARKING_TAZ))
            // Identify the parking stats entry by the (hour of event + parking taz) and store the outboundParkingOverheadTime
            parkingTaz match {
              case Some(taz) =>
                val parkingStats = parkingStatsByBinAndTaz.getOrElse(
                  hourOfEvent -> taz,
                  ParkingStatsCollector.ParkingStats(List.empty, List.empty, List.empty)
                )
                val outboundParkingTimes = parkingStats.outboundParkingTimeOverhead :+ outboundParkingTime
                parkingStatsByBinAndTaz.put(
                  hourOfEvent -> taz,
                  parkingStats.copy(outboundParkingTimeOverhead = outboundParkingTimes)
                )
              case None =>
                logger.error("No parking taz attribute defined for the LeavingParkingEvent")
            }
          }

        case _ if event.isInstanceOf[PathTraversalEvent] =>
          val pathTraversalEvent = event.asInstanceOf[PathTraversalEvent]
          if (personParkingStats.parkingTime.isDefined) {
            // Get the parking TAZ from the event
            val pathTraversalEventAttributes = pathTraversalEvent.getAttributes
            // Calculate the inbound parking overhead time
            val inboundParkingTime: Double = try {
              val arrivalTime = pathTraversalEventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toDouble
              arrivalTime - personParkingStats.parkingTime.get
            } catch {
              case e: Exception =>
                logger.error("Error while fetching and converting the parking cost : " + e.getMessage, e)
                0
            }
            // Compute the hour of event occurrence
            val hourOfEvent = (pathTraversalEvent.getTime / 3600).toInt
            /*
               Identify the parking stats entry by the (hour of event + parking taz) and
               store the inboundParkingOverheadTime and inboundParkingOverheadCost
             */
            val parkingStats = parkingStatsByBinAndTaz.getOrElse(
              hourOfEvent -> personParkingStats.parkingTAZId.getOrElse(""),
              ParkingStatsCollector.ParkingStats(List.empty, List.empty, List.empty)
            )
            val inboundParkingTimes = parkingStats.inboundParkingTimeOverhead :+ inboundParkingTime
            val inboundParkingCosts
              : List[Double] = parkingStats.inboundParkingCostOverhead :+ personParkingStats.parkingCost
              .getOrElse(0D)
            parkingStatsByBinAndTaz.put(
              hourOfEvent -> personParkingStats.parkingTAZId.getOrElse(""),
              parkingStats
                .copy(
                  inboundParkingTimeOverhead = inboundParkingTimes,
                  inboundParkingCostOverhead = inboundParkingCosts
                )
            )
          }
        case _ =>
      }
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
  ): Unit = {
    try {
      val header = "timeBin,TAZ,outboundParkingOverheadTime,inboundParkingOverheadTime,inboundParkingOverheadCost"
      val csvFilePath =
        GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileBaseName + ".csv")
      val data = parkingStatsByBinAndTaz map {
        case ((bin, taz), parkingStats) =>
          val outboundParkingTime: Double = parkingStats.outboundParkingTimeOverhead match {
            case _ if parkingStats.outboundParkingTimeOverhead.isEmpty => 0D
            case _ if parkingStats.outboundParkingTimeOverhead.size == 1 =>
              parkingStats.outboundParkingTimeOverhead.head
            case _ => parkingStats.outboundParkingTimeOverhead.sum / parkingStats.outboundParkingTimeOverhead.size
          }
          val inboundParkingTime: Double = parkingStats.inboundParkingTimeOverhead match {
            case _ if parkingStats.inboundParkingTimeOverhead.isEmpty   => 0D
            case _ if parkingStats.inboundParkingTimeOverhead.size == 1 => parkingStats.inboundParkingTimeOverhead.head
            case _                                                      => parkingStats.inboundParkingTimeOverhead.sum / parkingStats.inboundParkingTimeOverhead.size
          }
          val inboundParkingCost: Double = parkingStats.inboundParkingCostOverhead match {
            case _ if parkingStats.inboundParkingCostOverhead.isEmpty   => 0D
            case _ if parkingStats.inboundParkingCostOverhead.size == 1 => parkingStats.inboundParkingCostOverhead.head
            case _                                                      => parkingStats.inboundParkingCostOverhead.sum / parkingStats.inboundParkingCostOverhead.size
          }
          bin + "," +
          taz + "," +
          outboundParkingTime + "," +
          inboundParkingTime + "," +
          inboundParkingCost
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
    personParkingStatsTracker.clear()
    parkingStatsByBinAndTaz.clear()
  }

}

object ParkingStatsCollector extends OutputDataDescriptor {

  case class ParkingStats(
    outboundParkingTimeOverhead: List[Double],
    inboundParkingTimeOverhead: List[Double],
    inboundParkingCostOverhead: List[Double]
  )

  case class PersonParkingStats(
    departureTime: Option[Double],
    parkingTime: Option[Double],
    parkingCost: Option[Double],
    parkingTAZId: Option[String]
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
