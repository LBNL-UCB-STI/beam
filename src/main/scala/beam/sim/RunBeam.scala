package beam.sim

import java.nio.file.{Files, Paths}

import beam.agentsim.events.handling.BeamEventsHandling
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.controler.corelisteners.BeamPrepareForSimImpl
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.utils.FileUtils
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, DumpDataAtEnd, EventsHandling}
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
        install(new ControlerDefaultsModule)
        install(new ControlerDefaultCoreListenersModule)


        // Beam Inject below:
        install(new ConfigModule(typesafeConfig))
        install(new BeamAgentModule(BeamConfig(typesafeConfig)))
        install(new UtilsModule)
      }
    }).asJava, new AbstractModule() {
      override def install(): Unit = {
        // Override MATSim Defaults
        bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSimImpl])
        bind(classOf[DumpDataAtEnd]).toInstance(new DumpDataAtEnd {}) // Don't dump data at end.

        // Beam -> MATSim Wirings
        bindMobsim().to(classOf[BeamMobsim])
        addControlerListenerBinding().to(classOf[BeamSim])
        bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
        bind(classOf[EventsManager]).toInstance(EventsUtils.createEventsManager())
        bind(classOf[BeamConfig]).toInstance(BeamConfig(typesafeConfig))
      }
    }))

  def rumBeamWithConfigFile(configFileName: Option[String]) = {
    val inputDir = sys.env.get("BEAM_SHARED_INPUTS")
    val config = configFileName match {
      case Some(fileName) if Files.exists(Paths.get(fileName)) =>
        ConfigFactory.parseFile(Paths.get(fileName).toFile).resolve()
      case Some(fileName) if inputDir.isDefined && Files.exists(Paths.get(inputDir.get, fileName)) =>
        ConfigFactory.parseFile(Paths.get(inputDir.get, fileName).toFile).resolve()
      case Some(fileName) if getClass.getClassLoader.getResources(fileName).hasMoreElements =>
        ConfigFactory.parseResources(fileName).resolve()
      case _ =>
        ConfigFactory.parseResources("beam.conf").resolve()
    }
    runBeamWithConfig(config)
  }

  def runBeamWithConfig(config: com.typesafe.config.Config) = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()

    val beamConfig = BeamConfig(config)

    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)


    FileUtils.setConfigOutputFile(beamConfig.beam.outputs.outputDirectory, beamConfig.beam.agentsim.simulationName, matsimConfig)


    lazy val scenario = ScenarioUtils.loadScenario(matsimConfig)
    val injector = beamInjector(scenario, config)

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
