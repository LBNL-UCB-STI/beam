package beam.router.r5

import java.time.LocalTime

import beam.sim.config.BeamConfig
import beam.utils.CsvFileUtils
import com.conveyal.r5.analyst.scenario.{AddTrips, AdjustFrequency, Scenario}

import scala.collection.JavaConverters._

case class FrequencyAdjustingNetworkCoordinator(beamConfig: BeamConfig) extends NetworkCoordinator {

  override def postLoadNetwork(): Unit = {
    this.transportNetwork.transitLayer.buildDistanceTables(null)
    this.transportNetwork = buildFrequencyAdjustmentScenario.applyToTransportNetwork(transportNetwork)
    super.postLoadNetwork()
  }

  private def buildFrequencyAdjustmentScenario: Scenario = {
    val scenario = new Scenario()

    loadFrequencyAdjustments()
      .groupBy(_.routeId)
      .map {
        case (routeId, adjustments) =>
          new AdjustFrequency {
            route = routeId
            entries = adjustments.map(adjustTripFrequency).toList.asJava
          }
      }
      .foreach { adjustFrequency =>
        scenario.modifications.add(adjustFrequency)
      }

    scenario
  }

  def loadFrequencyAdjustments(): Set[FrequencyAdjustment] =
    CsvFileUtils
      .readCsvFileByLineToList(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile) { row =>
        FrequencyAdjustment(
          row.get("trip_id").split("-").head,
          row.get("trip_id"),
          LocalTime.parse(row.get("start_time")),
          LocalTime.parse(row.get("end_time")),
          row.get("headway_secs").toInt,
          Option(row.get("exact_times")).map(_.toInt)
        )
      }
      .toSet

  private def adjustTripFrequency(freqAdjustment: FrequencyAdjustment): AddTrips.PatternTimetable = {
    val entry = new AddTrips.PatternTimetable
    entry.headwaySecs = freqAdjustment.headwaySecs
    entry.startTime = freqAdjustment.startTime.toSecondOfDay
    entry.endTime = freqAdjustment.endTime.toSecondOfDay
    entry.sunday = true
    entry.monday = true
    entry.tuesday = true
    entry.wednesday = true
    entry.thursday = true
    entry.friday = true
    entry.saturday = true
    entry.sourceTrip = freqAdjustment.tripId
    entry
  }

  case class FrequencyAdjustment(
    routeId: String,
    tripId: String,
    startTime: LocalTime,
    endTime: LocalTime,
    headwaySecs: Int,
    exactTimes: Option[Int] = None
  )
}
