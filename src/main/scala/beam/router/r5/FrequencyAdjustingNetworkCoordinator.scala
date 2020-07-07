package beam.router.r5

import java.nio.file.{Files, Paths}

import beam.sim.config.BeamConfig
import beam.utils.transit.FrequencyAdjustment
import beam.utils.transit.FrequencyAdjustmentsUtils._
import com.conveyal.r5.analyst.scenario.{AddTrips, AdjustFrequency, Scenario}

import scala.collection.JavaConverters._

case class FrequencyAdjustingNetworkCoordinator(beamConfig: BeamConfig) extends NetworkCoordinator {

  val frequencyAdjustmentFile: String = beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile
    .getOrElse(throw new RuntimeException("frequencyAdjustmentFile value is empty"))

  override def postLoadNetwork(): Unit = {
    if (!Files.exists(Paths.get(frequencyAdjustmentFile))) {
      generateFrequencyAdjustmentsCsvFile(this.transportNetwork.transitLayer, frequencyAdjustmentFile)
    }

    this.transportNetwork.transitLayer.buildDistanceTables(null)
    this.transportNetwork =
      buildFrequencyAdjustmentScenario(loadFrequencyAdjustmentsFromCsvFile(frequencyAdjustmentFile))
        .applyToTransportNetwork(transportNetwork)
  }

  private def buildFrequencyAdjustmentScenario(frequencyAdjustments: Set[FrequencyAdjustment]): Scenario = {
    val scenario = new Scenario()

    frequencyAdjustments
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
