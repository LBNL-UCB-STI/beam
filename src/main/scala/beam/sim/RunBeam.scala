package beam.sim

import beam.sim.config.{BeamConfig, BeamLoggingSetup, ConfigModule}
import beam.sim.controler.BeamControler
import beam.sim.controler.corelisteners.{BeamControllerCoreListenersModule, BeamPrepareForSimImpl}
import beam.sim.modules.{AgentsimModule, BeamAgentModule, UtilsModule}
import beam.utils.FileUtils
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.controler._
import org.matsim.core.events.EventsUtils
import org.matsim.core.scenario.{ScenarioByInstanceModule, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

trait RunBeam {

  /**
    * mBeamConfig optional parameter is used to add custom BeamConfig instance to application injector
    */
  def beamInjector(scenario: Scenario, typesafeConfig: com.typesafe.config.Config): com.google.inject.Injector =
    org.matsim.core.controler.Injector.createInjector(scenario.getConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        install(new NewControlerModule)
        install(new ScenarioByInstanceModule(scenario))
        install(new controler.ControlerDefaultsModule)
        install(new BeamControllerCoreListenersModule)


        // Beam Inject below:
        install(new ConfigModule(typesafeConfig))
        install(new AgentsimModule)
        install(new BeamAgentModule(BeamConfig(typesafeConfig)))
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
        bind(classOf[BeamConfig]).toInstance(BeamConfig(typesafeConfig))
      }
    }))

  def rumBeamWithConfigFile(configFileName: Option[String]) = {
    val config = ConfigModule.loadConfig(configFileName)
    val matsimConfig = ConfigModule.matSimConfig(config)
    runBeamWithConfig(config, matsimConfig)
  }

  def runBeamWithConfig(typesafeConfig: com.typesafe.config.Config, matsimConfig: Config) = {
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
    val beamConfig = BeamConfig(typesafeConfig)
    BeamLoggingSetup.configureLogs(beamConfig)

    FileUtils.setConfigOutputFile(beamConfig.beam.outputs.outputDirectory, beamConfig.beam.agentsim.simulationName, matsimConfig)


    lazy val scenario = ScenarioUtils.loadScenario(matsimConfig)
    val injector = beamInjector(scenario, typesafeConfig)

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
