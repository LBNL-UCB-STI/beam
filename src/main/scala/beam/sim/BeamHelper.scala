package beam.sim

import java.io.FileOutputStream
import java.nio.file.{Files, InvalidPathException, Paths}
import java.util.Properties

import beam.agentsim.agents.rideHail.RideHailSurgePricingManager
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.handling.BeamEventsHandling
import beam.agentsim.infrastructure.TAZTreeMap
import beam.replanning.utilitybased.UtilityBasedModeChoice
import beam.replanning._
import beam.router.r5.NetworkCoordinator
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.metrics.Metrics
import beam.sim.metrics.Metrics.MetricLevel
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.utils.reflection.ReflectionUtils
import beam.utils.{BeamConfigUtils, FileUtils, LoggingUtil}
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, EventsHandling}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioByInstanceModule, ScenarioUtils}
import org.matsim.utils.objectattributes.AttributeConverter

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

trait BeamHelper {
  val log: Logger = Logger.getLogger(classOf[BeamHelper])

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
      private val mapper = new ObjectMapper()
      mapper.registerModule(DefaultScalaModule)

      override def install(): Unit = {
        val beamConfig = BeamConfig(typesafeConfig)

        val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption
        mTazTreeMap.foreach{ tazTreeMap =>
          bind(classOf[TAZTreeMap]).toInstance(tazTreeMap)
        }

        bind(classOf[BeamConfig]).toInstance(beamConfig)
        bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
        bind(classOf[RideHailSurgePricingManager]).toInstance(new RideHailSurgePricingManager(beamConfig, mTazTreeMap))

        addControlerListenerBinding().to(classOf[BeamSim])
        bindMobsim().to(classOf[BeamMobsim])
        bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
        bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory])
        if (getConfig.strategy().getPlanSelectorForRemoval == "tryToKeepOneOfEachClass") {
          bindPlanSelectorForRemoval().to(classOf[TryToKeepOneOfEachClass])
        }
        addPlanStrategyBinding("GrabExperiencedPlan").to(classOf[GrabExperiencedPlan])
        addPlanStrategyBinding("SwitchModalityStyle").toProvider(classOf[SwitchModalityStyle])
        addPlanStrategyBinding("ClearRoutes").toProvider(classOf[ClearRoutes])
        addPlanStrategyBinding(BeamReplanningStrategy.UtilityBasedModeChoice.toString).toProvider(classOf[UtilityBasedModeChoice])
        addAttributeConverterBinding(classOf[MapStringDouble]).toInstance(new AttributeConverter[MapStringDouble] {
          override def convertToString(o: scala.Any): String = mapper.writeValueAsString(o.asInstanceOf[MapStringDouble].data)

          override def convert(value: String): MapStringDouble = MapStringDouble(mapper.readValue(value, classOf[Map[String, Double]]))
        })
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

    val beamConfig = BeamConfig(config)
    Metrics.level = beamConfig.beam.metrics.level

    if (Metrics.isMetricsEnable()) Kamon.start(config.withFallback(ConfigFactory.defaultReference()))

    val (_, outputDirectory) = runBeamWithConfig(config)

    val props = new Properties()
    props.setProperty("commitHash", LoggingUtil.getCommitHash)
    props.setProperty("configFile", cfgFile)
    val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
    props.store(out, "Simulation out put props.")
    if (beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equalsIgnoreCase("ModeChoiceLCCM")) {
      Files.copy(Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.paramFile), Paths.get(outputDirectory, Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.paramFile).getFileName.toString))
    }
    Files.copy(Paths.get(cfgFile), Paths.get(outputDirectory, "beam.conf"))

    if (Metrics.isMetricsEnable()) Kamon.shutdown()
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

case class MapStringDouble(data: Map[String, Double])