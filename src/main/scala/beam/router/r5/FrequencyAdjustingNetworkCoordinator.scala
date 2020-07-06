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

  /** Loads CSV in the following format:
    * {{{
    *   trip_id,start_time,end_time,headway_secs,exact_times
    *   bus:B1-EAST-1,06:00:00,22:00:00,150,
    *   train:R2-NORTH-1,07:00:00,23:00:00,300,
    *   ...
    * }}}
    * where "bus:", "train:" etc. prefixes for tripId are the names of GTFS feeds,
    * everything else have the same format as <a href="https://developers.google.com/transit/gtfs/reference#frequenciestxt">frequencies.txt</a>
    * <br/>
    * Therefore part "bus:B1" will be considered as "routeId", whole thing "bus:B1-EAST-1" - as a tripId
    * in the [[com.conveyal.r5.transit.TransportNetwork#transitLayer]]
    *
    * @return set of FrequencyAdjustment objects
    */
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
