package beam.sim

import beam.agentsim.events.handling.BeamEventsHandling
import beam.sim.config.{BeamConfig, BeamLoggingSetup, ConfigModule}
import beam.sim.modules.{AgentsimModule, BeamAgentModule, UtilsModule}
import beam.sim.controler.corelisteners.{BeamControllerCoreListenersModule, BeamPrepareForSimImpl}
import beam.sim.controler.BeamControler
import beam.utils.FileUtils
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler._
import org.matsim.core.events.{EventsUtils, ParallelEventsManagerImpl}
import org.matsim.core.scenario.{ScenarioByInstanceModule, ScenarioUtils}
import net.codingwell.scalaguice.InjectorExtensions._
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsUtils

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

trait RunBeam {

  /**
    * mBeamConfig optional parameter is used to add custom BeamConfig instance to application injector
    */
  def beamInjector(scenario: Scenario,  matSimConfig: Config,mBeamConfig: Option[BeamConfig] = None): com.google.inject.Injector =
    org.matsim.core.controler.Injector.createInjector(matSimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        install(new NewControlerModule)
        install(new ScenarioByInstanceModule(scenario))
        install(new controler.ControlerDefaultsModule)
        install(new BeamControllerCoreListenersModule)


        // Beam Inject below:
        install(new ConfigModule)
        install(new AgentsimModule)
        install(new BeamAgentModule)
        install(new UtilsModule)
      }
    }).asJava, new AbstractModule() {
      override def install(): Unit = {
        // Override MATSim Defaults
        bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSimImpl])

        // Beam -> MATSim Wirings
        bindMobsim().to(classOf[BeamMobsim]) //TODO: This will change
        addControlerListenerBinding().to(classOf[BeamSim])
        bind(classOf[EventsManager]).toInstance(EventsUtils.createEventsManager())
        bind(classOf[ControlerI]).to(classOf[BeamControler]).asEagerSingleton()
        mBeamConfig.foreach(beamConfig => bind(classOf[BeamConfig]).toInstance(beamConfig)) //Used for testing - if none passed, app will use factory BeamConfig
      }
    }))

  def rumBeamWithConfigFile(configFileName: Option[String]) = {
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)

    //set config filename before Guice start init procedure
    ConfigModule.ConfigFileName = configFileName

    // Inject and use tsConfig instead here
    // Make implicit to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
    FileUtils.setConfigOutputFile(ConfigModule.beamConfig.beam.outputs.outputDirectory, ConfigModule.beamConfig.beam.agentsim.simulationName, ConfigModule.matSimConfig)

    BeamLoggingSetup.configureLogs(ConfigModule.beamConfig)

    lazy val scenario = ScenarioUtils.loadScenario(ConfigModule.matSimConfig)
    val injector = beamInjector(scenario, ConfigModule.matSimConfig)
    val services: BeamServices = injector.getInstance(classOf[BeamServices])

    services.controler.run()
  }

  /*
  Used for testing - runs BEAM with custom BeamConfig object instead of default BeamConfig factory
  * */
  def runBeamWithConfig(beamConfig: BeamConfig, matsimConfig: Config) = {
    // Inject and use tsConfig instead here
    // Make implicit to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
    FileUtils.setConfigOutputFile(beamConfig.beam.outputs.outputDirectory, beamConfig.beam.agentsim.simulationName, matsimConfig)

    BeamLoggingSetup.configureLogs(beamConfig)

    lazy val scenario = ScenarioUtils.loadScenario(matsimConfig)
    val injector = beamInjector(scenario, matsimConfig, Some(beamConfig))

    val services: BeamServices = injector.getInstance(classOf[BeamServices])

    services.controler.run()

  }
}

object RunBeam extends RunBeam with App{
  print("""
  ________
  ___  __ )__________ _______ ___
  __  __  |  _ \  __ `/_  __ `__ \
  _  /_/ //  __/ /_/ /_  / / / / /
  /_____/ \___/\__,_/ /_/ /_/ /_/

 _____________________________________

 """)
  def parseArgs() = {

    args.sliding(2, 1).toList.collect {
      case Array("--config", configName: String) if configName.trim.nonEmpty => ("config", configName)
      //case Array("--anotherParamName", value: String)  => ("anotherParamName", value)
      case arg@_ => throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }

  val argsMap = parseArgs()

  rumBeamWithConfigFile(argsMap.get("config"))
}
