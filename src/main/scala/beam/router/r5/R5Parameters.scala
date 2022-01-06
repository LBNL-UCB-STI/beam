package beam.router.r5

import java.time.ZonedDateTime

import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile}
import beam.utils.{DateUtils, FileUtils, LoggingUtil, NetworkHelper, NetworkHelperImpl}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.Config
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

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

  def fromConfig(config: Config): R5Parameters = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
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
    )
  }
}
