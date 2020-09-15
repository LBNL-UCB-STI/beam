package beam.router.r5

import beam.sim.config.BeamConfig
import beam.utils.transit.FrequencyAdjustmentUtils._
import com.conveyal.r5.analyst.scenario.{AddTrips, AdjustFrequency, Scenario}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.network.Network

import scala.collection.JavaConverters._

case class FrequencyAdjustingNetworkCoordinator(beamConfig: BeamConfig) extends NetworkCoordinator {

  override var transportNetwork: TransportNetwork = _
  override var network: Network = _

  override def postLoadNetwork(): Unit = {
    val freqAdjustments = loadFrequencyAdjustmentCsvFile(
      beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile
    )

    this.transportNetwork.transitLayer.buildDistanceTables(null)
    this.transportNetwork = buildFrequencyAdjustmentScenario(freqAdjustments).applyToTransportNetwork(transportNetwork)
  }

  private def buildFrequencyAdjustmentScenario(frequencyAdjustments: Set[FrequencyAdjustment]): Scenario = {
    val scenario = new Scenario()

    frequencyAdjustments
      .groupBy(_.routeId)
      .foreach {
        case (routeId, adjustments) =>
          val adjustFrequency = new AdjustFrequency
          adjustFrequency.route = routeId
          adjustFrequency.entries = adjustments.map(adjustTripFrequency).toList.asJava

          scenario.modifications.add(adjustFrequency)
      }

    scenario
  }

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
}
