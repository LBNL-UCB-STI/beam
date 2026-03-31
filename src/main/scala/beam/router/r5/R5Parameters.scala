package beam.router.r5

import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile}
import beam.utils._
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network

import java.time.ZonedDateTime

case class R5Parameters(
  beamConfig: BeamConfig,
  transportNetwork: TransportNetwork,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  fuelTypePrices: Map[FuelType, Double],
  ptFares: PtFares,
  geo: GeoUtils,
  dates: DateUtils,
  networkHelper: NetworkHelper,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator
)

object R5Parameters {

  def outputPointerName(config: Config): String = {
    val host = if (config.hasPath("node.host")) config.getString("node.host").replaceAll("[^A-Za-z0-9._-]", "_") else "local"
    val port = if (config.hasPath("node.port")) config.getString("node.port").replaceAll("[^A-Za-z0-9._-]", "_") else "na"
    s"latest-routing-worker-output-$host-$port.txt"
  }

  def outputDirectory(config: Config, beamConfig: BeamConfig): String = {
    if (config.hasPath("beam.cluster.workerOutputDirectory"))
      config.getString("beam.cluster.workerOutputDirectory")
    else
      FileUtils.getConfigOutputFile(
        beamConfig.beam.outputs.baseOutputDirectory,
        beamConfig.beam.agentsim.simulationName,
        beamConfig.beam.outputs.addTimestampToOutputDirectory
      )
  }

  def fromConfig(config: Config): (R5Parameters, Option[(TransportNetwork, Network)]) = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = R5Parameters.outputDirectory(config, beamConfig)
    val pointerPath = FileUtils.writeOutputDirectoryPointer(
      beamConfig.beam.outputs.baseOutputDirectory,
      outputPointerName(config),
      outputDirectory
    )
    println(s"[ROUTING-WORKER-OUTPUT] $outputDirectory")
    println(s"[ROUTING-WORKER-OUTPUT-POINTER] ${pointerPath.toAbsolutePath}")
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.init()
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    LoggingUtil.initLogger(outputDirectory, beamConfig.beam.logger.keepConsoleAppenderOn)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val dates: DateUtils = DateUtils(
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
    )
    val geo = new GeoUtilsImpl(beamConfig)
    val vehicleTypes = readBeamVehicleTypeFile(beamConfig)
    val fuelTypePrices = readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap
    val ptFares = PtFares(beamConfig.beam.agentsim.agents.ptFare.filePath)
    val fareCalculator = new FareCalculator(beamConfig)
    val tollCalculator = new TollCalculator(beamConfig)
    BeamRouter.checkForConsistentTimeZoneOffsets(dates, networkCoordinator.transportNetwork)
    (
      R5Parameters(
        beamConfig = beamConfig,
        transportNetwork = networkCoordinator.transportNetwork,
        vehicleTypes = vehicleTypes,
        fuelTypePrices = fuelTypePrices,
        ptFares = ptFares,
        geo = geo,
        dates = dates,
        networkHelper = new NetworkHelperImpl(networkCoordinator.network),
        fareCalculator = fareCalculator,
        tollCalculator = tollCalculator
      ),
      networkCoordinator.networks2
    )
  }
}
