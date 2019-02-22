package beam.utils
import java.io.File

import beam.agentsim.agents.vehicles.{VehicleCsvReader, VehicleEnergy}
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import com.google.inject.util.Providers
import com.google.inject.{AbstractModule, Guice, Injector, Provider}
import org.matsim.analysis.{CalcLinkStats, IterationStopWatch, ScoreStats, VolumesAnalyzer}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.controler.{ControlerI, MatsimServices, OutputDirectoryHierarchy}
import org.matsim.core.replanning.StrategyManager
import org.matsim.core.router.TripRouter
import org.matsim.core.router.costcalculators.TravelDisutilityFactory
import org.matsim.core.router.util.{LeastCostPathCalculatorFactory, TravelDisutility, TravelTime}
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.scoring.ScoringFunctionFactory

private class MatsimServicesMock(
  override val getControlerIO: OutputDirectoryHierarchy,
  override val getScenario: Scenario
) extends MatsimServices {
  override def getStopwatch: IterationStopWatch = null
  override def getLinkTravelTimes: TravelTime = null
  override def getTripRouterProvider: Provider[TripRouter] = null
  override def createTravelDisutilityCalculator(): TravelDisutility = null
  override def getLeastCostPathCalculatorFactory: LeastCostPathCalculatorFactory = null
  override def getScoringFunctionFactory: ScoringFunctionFactory = null
  override def getConfig: Config = null
  override def getEvents: EventsManager = null
  override def getInjector: Injector = null
  override def getLinkStats: CalcLinkStats = null
  override def getVolumes: VolumesAnalyzer = null
  override def getScoreStats: ScoreStats = null
  override def getTravelDisutilityFactory: TravelDisutilityFactory = null
  override def getStrategyManager: StrategyManager = null
  override def addControlerListener(controlerListener: ControlerListener): Unit = {}
  override def getIterationNumber: Integer = null
}

abstract class SimRunnerForTest {
  def config: com.typesafe.config.Config
  def basePath: String = new File("").getAbsolutePath
  def testOutputDir: String = TestConfigUtils.testOutputDir
  def outputDirPath: String

  // Next things are pretty cheap in initialization, so let it be non-lazy
  val beamCfg = BeamConfig(config)
  val vehicleCsvReader = new VehicleCsvReader(beamCfg)

  val vehicleEnergy =
    new VehicleEnergy(vehicleCsvReader.getVehicleEnergyRecordsUsing, vehicleCsvReader.getLinkToGradeRecordsUsing)
  val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
  val fareCalculator = new FareCalculator(beamCfg.beam.routing.r5.directory)
  val tollCalculator = new TollCalculator(beamCfg)
  val geoUtil = new GeoUtilsImpl(beamCfg)

  val outputDirectoryHierarchy: OutputDirectoryHierarchy = {
    val odh =
      new OutputDirectoryHierarchy(outputDirPath, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
    odh.createIterationDirectory(0)
    odh
  }

  lazy val matsimSvc: MatsimServices = new MatsimServicesMock(outputDirectoryHierarchy, scenario)
  lazy val scenario = ScenarioUtils.loadScenario(matsimConfig)
  lazy val networkCoordinator = {
    val nc = DefaultNetworkCoordinator(beamCfg)
    nc.loadNetwork()
    nc.convertFrequenciesToTrips()
    nc
  }
  lazy val networkHelper = new NetworkHelperImpl(networkCoordinator.network)
  lazy val injector = Guice.createInjector(new AbstractModule() {
    protected def configure(): Unit = {
      bind(classOf[BeamConfig]).toInstance(beamCfg)
      bind(classOf[beam.sim.common.GeoUtils]).toInstance(geoUtil)
      bind(classOf[NetworkHelper]).toInstance(networkHelper)
      bind(classOf[ControlerI]).toProvider(Providers.of(null))
      bind(classOf[VehicleEnergy]).toInstance(vehicleEnergy)
    }
  })
}
