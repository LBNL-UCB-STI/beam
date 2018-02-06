package beam.sim

import java.io.FileOutputStream
import java.nio.file.{Files, InvalidPathException, Paths}
import java.util
import java.util.Properties

import beam.agentsim.events.handling.BeamEventsHandling
import beam.replanning.GrabExperiencedPlan
import beam.router.r5.NetworkCoordinator
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.utils.{BeamConfigUtils, FileUtils, LoggingUtil}
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.config.Config
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, DumpDataAtEnd, EventsHandling}
import org.matsim.core.scenario.{MutableScenario, ScenarioByInstanceModule, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

trait BeamHelper {

  def module(typesafeConfig: com.typesafe.config.Config, scenario: Scenario, transportNetwork: TransportNetwork): com.google.inject.Module = AbstractModule.`override`(
    ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        install(new NewControlerModule)
        install(new ScenarioByInstanceModule(scenario))
        install(new ControlerDefaultsModule)
        install(new ControlerDefaultCoreListenersModule)


        // Beam Inject below:
        install(new ConfigModule(typesafeConfig))
        install(new BeamAgentModule(BeamConfig(typesafeConfig)))
        install(new UtilsModule)
      }
    }).asJava, new AbstractModule() {
      override def install(): Unit = {
        bind(classOf[BeamConfig]).toInstance(BeamConfig(typesafeConfig))
        bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
        addControlerListenerBinding().to(classOf[BeamSim])
        bindMobsim().to(classOf[BeamMobsim])
        bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
        bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory]);
        addPlanStrategyBinding("GrabExperiencedPlan").to(classOf[GrabExperiencedPlan])
        bind(classOf[DumpDataAtEnd]).toInstance(new DumpDataAtEnd {}) // Don't dump data at end.

        bind(classOf[TransportNetwork]).toInstance(transportNetwork)
      }
    })

  def runBeamWithConfigFile(configFileName: Option[String]): Unit = {
    val (config, cfgFile) = configFileName match {
      case Some(fileName) =>
        (BeamConfigUtils.parseFileSubstitutingInputDirectory(fileName), fileName)
      case _ =>
        throw new InvalidPathException("null", "invalid configuration file.")
    }

    val (_, outputDirectory) = runBeamWithConfig(config)
    val beamConfig = BeamConfig(config)

    val props = new Properties()
    props.setProperty("commitHash", LoggingUtil.getCommitHash)
    props.setProperty("configFile", cfgFile)
    val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
    props.store(out, "Simulation out put props.")
    Files.copy(Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile), Paths.get(outputDirectory, "modeChoiceParameters.xml"))
    Files.copy(Paths.get(cfgFile), Paths.get(outputDirectory, "beam.conf"))
  }

  def runBeamWithConfig(config: com.typesafe.config.Config): (Config, String) = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

    val beamConfig = BeamConfig(config)

    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)

    val outputDirectory = FileUtils.getConfigOutputFile(beamConfig.beam.outputs.baseOutputDirectory, beamConfig.beam.agentsim.simulationName, beamConfig.beam.outputs.addTimestampToOutputDirectory)
    LoggingUtil.createFileLogger(outputDirectory)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = new NetworkCoordinator(beamConfig, scenario.getTransitVehicles)
    networkCoordinator.loadNetwork()
    scenario.setNetwork(networkCoordinator.network)

    val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, module(config, scenario, networkCoordinator.transportNetwork))

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

    beamServices.controler.run()
    (matsimConfig, outputDirectory)
  }
}
