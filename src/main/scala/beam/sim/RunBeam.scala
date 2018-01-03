package beam.sim

import java.nio.file.{Files, Paths}

import beam.agentsim.events.handling.BeamEventsHandling
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.utils.FileUtils
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.Config
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, DumpDataAtEnd, EventsHandling}
import org.matsim.core.scenario.{MutableScenario, ScenarioByInstanceModule, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

trait RunBeam {

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
        // Override MATSim Defaults
        bind(classOf[PrepareForSim]).toInstance(new PrepareForSim {
          override def run(): Unit = {}
        }) // Nothing to do
        bind(classOf[DumpDataAtEnd]).toInstance(new DumpDataAtEnd {}) // Don't dump data at end.
//        bind(classOf[EventsManager]).to(classOf[EventsManagerImpl]).asEagerSingleton()

        // Beam -> MATSim Wirings
        bindMobsim().to(classOf[BeamMobsim])
        addControlerListenerBinding().to(classOf[BeamSim])
        bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
        bind(classOf[BeamConfig]).toInstance(BeamConfig(typesafeConfig))

        bind(classOf[TransportNetwork]).toInstance(transportNetwork)
      }
    })

  def rumBeamWithConfigFile(configFileName: Option[String]) = {
    val config = configFileName match {
      case Some(fileName) =>
        ConfigFactory.parseFile(Paths.get(fileName).toFile).resolve()
    }
    runBeamWithConfig(config)
  }

  def runBeamWithConfig(config: com.typesafe.config.Config): Config = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()

    val beamConfig = BeamConfig(config)

    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)

    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = new NetworkCoordinator(beamConfig, scenario.getTransitVehicles)
    networkCoordinator.loadNetwork()
    scenario.setNetwork(networkCoordinator.network)

    val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, module(config, scenario, networkCoordinator.transportNetwork))

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

    val envelopeInUTM = beamServices.geo.wgs2Utm(networkCoordinator.transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer

    beamServices.controler.run()
matsimConfig
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
