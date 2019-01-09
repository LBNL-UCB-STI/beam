package beam.analysis
import java.{lang, util}

import beam.agentsim.events.{LeavingParkingEvent, LeavingParkingEventAttrs, ModeChoiceEvent, ReserveRideHailEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.analysis.plots.{GraphAnalysis, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class ParkingStatsCollector(beamServices: BeamServices) extends GraphAnalysis {
  private val personDepartures : mutable.LinkedHashMap[Id[Person],Option[Double]] = mutable.LinkedHashMap.empty[Id[Person],Option[Double]]
  private val parkingTimeByBinAndTaz : mutable.LinkedHashMap[(Int,String),List[Double]] = mutable.LinkedHashMap.empty[(Int,String),List[Double]]

  override def createGraph(event: IterationEndsEvent): Unit = {

  }

  override def processStats(event: Event): Unit = {
    /* When a person reserves a ride hail, push the person into the ride hail waiting queue and once the person
        enters a vehicle , compute the difference between the times of occurrence for both the events as `waiting time`.*/
    event match {
      case modeChoiceEvent: ModeChoiceEvent =>
        val modeChoiceEventAttributes = modeChoiceEvent.getAttributes
        val modeChoice: String = modeChoiceEventAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
        modeChoice match {
          case BeamMode.CAR.value | BeamMode.DRIVE_TRANSIT.value =>
            personDepartures.put(modeChoiceEvent.getPersonId,None)
          case _ =>
        }
      case personDepartureEvent: PersonDepartureEvent =>
        if (personDepartures.contains(personDepartureEvent.getPersonId)) {
          personDepartures.put(personDepartureEvent.getPersonId,Some(personDepartureEvent.getTime))
        }
      case personEntersVehicleEvent: PersonEntersVehicleEvent =>
        if (personDepartures.contains(personEntersVehicleEvent.getPersonId) && personEntersVehicleEvent.getVehicleId.toString.equals("transit")) {
          personDepartures.remove(personEntersVehicleEvent.getPersonId)
        }
      case leavingParkingEvent: LeavingParkingEvent =>
        if (personDepartures.contains(leavingParkingEvent.getPersonId)) {
          personDepartures.getOrElse(leavingParkingEvent.getPersonId,None) match {
            case Some(departureTime) =>
              processParkingStats(leavingParkingEvent,departureTime)
            case None =>
          }
        }
      case _ =>
    }
  }

  private def processParkingStats(leavingParkingEvent: LeavingParkingEvent, departureTime : Double) = {
    val outboundParkingOverheadTime = leavingParkingEvent.getTime - departureTime
    val leavingParkingEventAttributes = leavingParkingEvent.getAttributes
    val parkingTaz = leavingParkingEventAttributes.get(LeavingParkingEventAttrs.ATTRIBUTE_PARKING_TAZ)
    val hourOfEvent = (leavingParkingEvent.getTime / 3600).toInt
    var parkingTimes = parkingTimeByBinAndTaz.getOrElse(hourOfEvent -> parkingTaz,List.empty)
    parkingTimes = parkingTimes :+ outboundParkingOverheadTime
    parkingTimeByBinAndTaz.put(hourOfEvent -> parkingTaz,parkingTimes)
  }

  private def writeToCsv(iterationNumber: Int, parkingTimeByBinAndTaz : mutable.LinkedHashMap[(Int,String),List[Double]]) = {
    val header = "timeBin,TAZ,outboundParkingOverheadTime,inboundParkingOverheadTime,inboundParkingOverheadCost"
    val fileBaseName = "parkingStats"
    import beam.analysis.plots.GraphsStatsAgentSimEventsListener
    import org.matsim.core.utils.io.IOUtils
    val csvFileName =
      GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileBaseName + ".csv")
    val out = IOUtils.getBufferedWriter(csvFileName)
    val inboundParkingOverheadTime = 0
    val inboundParkingOverheadCost = 0
    val data = parkingTimeByBinAndTaz map { case ((bin,taz),parkingOverheadTimes) =>
      bin + "," + taz + "," +  parkingOverheadTimes.sum / parkingOverheadTimes.size + "," + inboundParkingOverheadTime + "," + inboundParkingOverheadCost
    } mkString "\n"
  }

  override def resetStats(): Unit = {
    personDepartures.clear()
    parkingTimeByBinAndTaz.clear()
  }

}
