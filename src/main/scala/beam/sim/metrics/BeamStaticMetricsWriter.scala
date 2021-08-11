package beam.sim.metrics

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ElectricCurrentType.DC
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.config.BeamConfig
import beam.sim.metrics.SimulationMetricCollector.{defaultMetricName, SimulationTime}
import beam.sim.{BeamScenario, BeamServices}
import org.matsim.core.scenario.MutableScenario

import java.io.File
import scala.util.Random

object BeamStaticMetricsWriter {

  def writeBaseMetrics(beamScenario: BeamScenario, beamServices: BeamServices): Unit = {
    beamServices.simMetricCollector.write("beam-run", SimulationTime(0))

    val envelopeInWGS = beamScenario.transportNetwork.streetLayer.envelope
    val values = Map(
      "Xmax" -> envelopeInWGS.getMaxX,
      "Xmin" -> envelopeInWGS.getMinX,
      "Ymax" -> envelopeInWGS.getMaxY,
      "Ymin" -> envelopeInWGS.getMinY
    )

    beamServices.simMetricCollector.write("beam-map-envelope", SimulationTime(0), values)
  }

  def writeSimulationParameters(
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    beamServices: BeamServices,
    beamConfig: BeamConfig
  ): Unit = {
    val numOfPersons = scenario.getPopulation.getPersons.size()
    val numOfHouseholds = scenario.getHouseholds.getHouseholds.values().size
    val privateFleetSize = beamScenario.privateVehicles.size

    def writeMetric(metric: String, value: Int): Unit = {
      beamServices.simMetricCollector.write(metric, SimulationTime(0), Map(defaultMetricName -> value))
    }

    def writeStrMetric(metric: String, value: String): Unit = {
      beamServices.simMetricCollector.writeStr(metric, SimulationTime(0), Map(defaultMetricName -> value))
    }

    writeMetric("beam-run-population-size", numOfPersons)
    writeMetric("beam-run-households", numOfHouseholds)
    writeMetric("beam-run-private-fleet-size", privateFleetSize)

    def fileExist(path: String): Boolean = {
      val f = new File(path)
      f.exists && !f.isDirectory
    }

    /*
      If both files are given then read parking stalls from both of them.
      beamConfig.beam.agentsim.taz.parkingFilePath
            provides public fast charge stalls and
      beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath
            provides charging depot stalls
     */

    def metricEnabled(metricName: String): Boolean = beamServices.simMetricCollector.metricEnabled(metricName)

    if (
      metricEnabled("beam-run-charging-depots-cnt") ||
      metricEnabled("beam-run-public-fast-charge-cnt") ||
      metricEnabled("beam-run-public-fast-charge-stalls-cnt") ||
      metricEnabled("beam-run-charging-depots-stalls-cnt")
    ) {

      val (chargingDepotsFilePath: String, publicFastChargerFilePath: String) = {
        if (
          fileExist(beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath) &&
          fileExist(beamConfig.beam.agentsim.taz.parkingFilePath)
        ) {
          (
            beamConfig.beam.agentsim.agents.rideHail.initialization.parking.filePath,
            beamConfig.beam.agentsim.taz.parkingFilePath
          )
        } else if (fileExist(beamConfig.beam.agentsim.taz.parkingFilePath)) {
          ("", beamConfig.beam.agentsim.taz.parkingFilePath)
        } else {
          ("", "")
        }
      }

      if (chargingDepotsFilePath.nonEmpty) {
        val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
        val parkingStallCountScalingFactor = beamServices.beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
        val (chargingDepots, _) =
          ParkingZoneFileUtils
            .fromFile[TAZ](
              chargingDepotsFilePath,
              rand,
              Some(beamScenario.beamConfig),
              parkingStallCountScalingFactor
            )

        var cntChargingDepots = 0
        var cntChargingDepotsStalls = 0

        chargingDepots.foreach { case (_, parkingZone) =>
          if (parkingZone.chargingPointType.nonEmpty) {
            cntChargingDepots += 1
            cntChargingDepotsStalls += parkingZone.stallsAvailable
          }
        }

        var cntPublicFastCharge = 0
        var cntPublicFastChargeStalls = 0
        if (publicFastChargerFilePath.nonEmpty) {
          val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
          val (publicChargers, _) =
            ParkingZoneFileUtils
              .fromFile[TAZ](
                publicFastChargerFilePath,
                rand,
                Some(beamConfig),
                parkingStallCountScalingFactor
              )

          publicChargers.foreach { case (_, publicCharger) =>
            if (publicCharger.chargingPointType.nonEmpty) {
              if (ChargingPointType.getChargingPointCurrent(publicCharger.chargingPointType.get) == DC) {
                cntPublicFastCharge += 1
                cntPublicFastChargeStalls += publicCharger.stallsAvailable
              }
            }
          }
        }

        writeMetric("beam-run-charging-depots-cnt", cntChargingDepots)

        //if (beamConfig.beam.agentsim.taz.parkingStallChargerInitMethod == "UNLIMITED") {
        if (cntPublicFastCharge < 0 || cntPublicFastChargeStalls < 0 || cntChargingDepotsStalls < 0) {
          writeStrMetric("beam-run-public-fast-charge-cnt", "UNLIMITED")
          writeStrMetric("beam-run-public-fast-charge-stalls-cnt", "UNLIMITED")
          writeStrMetric("beam-run-charging-depots-stalls-cnt", "UNLIMITED")
        } else {
          writeMetric("beam-run-public-fast-charge-cnt", cntPublicFastCharge)
          writeMetric("beam-run-public-fast-charge-stalls-cnt", cntPublicFastChargeStalls)
          writeMetric("beam-run-charging-depots-stalls-cnt", cntChargingDepotsStalls)
        }
      } else {
        writeStrMetric("beam-run-charging-depots-cnt", "NOFILE")
        writeStrMetric("beam-run-public-fast-charge-cnt", "NOFILE")
        writeStrMetric("beam-run-public-fast-charge-stalls-cnt", "NOFILE")
        writeStrMetric("beam-run-charging-depots-stalls-cnt", "NOFILE")
      }
    }
  }
}
