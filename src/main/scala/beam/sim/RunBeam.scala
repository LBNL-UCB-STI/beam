package beam.sim

import beam.sim.config.{ConfigModule}
import beam.sim.modules.{AgentsimModule, BeamAgentModule}
import beam.sim.controler.corelisteners.BeamControllerCoreListenersModule
import beam.sim.controler.BeamControler
import beam.utils.FileUtils
import org.matsim.core.controler._
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.scenario.{ScenarioByInstanceModule, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object RunBeam extends App{
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

  //set config filename before Guice start init procedure
  ConfigModule.ConfigFileName = argsMap.get("config")

  // Inject and use tsConfig instead here
  // Make implicit to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
  FileUtils.setConfigOutputFile(ConfigModule.beamConfig.beam.outputs.outputDirectory, ConfigModule.beamConfig.beam.agentsim.simulationName, ConfigModule.matSimConfig)

  //TODO this line can be safely deleted, just for exploring structure of config class
//  ConfigModule.beamConfig.beam.outputs.outputDirectory;

  private lazy val scenario = ScenarioUtils.loadScenario(ConfigModule.matSimConfig)
  val injector: com.google.inject.Injector =
  org.matsim.core.controler.Injector.createInjector(ConfigModule.matSimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        install(new NewControlerModule)
        install(new ScenarioByInstanceModule(scenario))
        install(new ControlerDefaultsModule)
        install(new BeamControllerCoreListenersModule)

        // Beam Inject below:
        install(new ConfigModule)
        install(new AgentsimModule)
        install(new BeamAgentModule)
      }
    }).asJava, new AbstractModule() {
      override def install(): Unit = {

        // Beam -> MATSim Wirings

        bindMobsim().to(classOf[QSim]) //TODO: This will change
        addControlerListenerBinding().to(classOf[BeamSim])
        bind(classOf[ControlerI]).to(classOf[BeamControler]).asEagerSingleton()
      }
    }))

  val services: BeamServices = injector.getInstance(classOf[BeamServices])
  services.controler.run()

}
