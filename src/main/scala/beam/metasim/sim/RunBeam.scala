package beam.metasim.sim

import akka.actor.{ActorRef, ActorSystem, Inbox}
import beam.metasim.agents.PersonAgent
import beam.metasim.akkaguice.GuiceAkkaExtension
import beam.metasim.sim.modules.{BeamActorSystemModule, BeamAgentModule, ConfigModule, MetaSimModule}
import beam.metasim.utils.FileUtils
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners._
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.{ScenarioByConfigModule, ScenarioUtils}
import eri.commons.config.SSConfig;

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

/**
  * Created by sfeygin on 1/30/17.
  */
object RunBeam extends App{
  import net.codingwell.scalaguice.InjectorExtensions._
  val ConfigRelPath =  "test/input/beam/actors/"
  val ConfigFileName = "config.xml"
  val SimName = "actors"
  val OutputDirectoryBase = "test/output/beam/basicTests/actors"

  val conf = new SSConfig()

  val config:Config = ConfigUtils.loadConfig(ConfigRelPath+ConfigFileName)
  FileUtils.setConfigOutputFile(OutputDirectoryBase,SimName,config)
  val scenario:Scenario = ScenarioUtils.loadScenario(config)
  val pop = scenario.getPopulation
  val injector: com.google.inject.Injector =
    Injector.createInjector(config, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        val routeConfigGroup = getConfig.plansCalcRoute
        install(new NewControlerModule)
        install(new ScenarioByConfigModule)
        install(new ControlerDefaultsModule)
        install(new ControlerDefaultCoreListenersModule)
        install(new ConfigModule)
        install(new BeamActorSystemModule)
        install(new MetaSimModule)
        install(new BeamAgentModule)
      }
    }),new AbstractModule() {
      override def install(): Unit = {
        bind(classOf[ControlerI]).to(classOf[ControlerImpl]).asEagerSingleton()
      }
    }))

  val system = injector.instance[ActorSystem]
  val inbox:Inbox = Inbox.create(system)
  val personAgent:ActorRef = system.actorOf(GuiceAkkaExtension(system).props(PersonAgent.name))

  val activity0=PopulationUtils.createActivityFromLinkId("h1",Id.createLinkId(11))
  val activity1=PopulationUtils.createActivityFromLinkId("w1",Id.createLinkId(23))

//  personAgent ! StartDay
//  personAgent ! InitActivity(activity0)
//  personAgent ! DepartActivity(activity1)

  val controler = injector.instance[ControlerI]

  controler.run()

  system.terminate()

}

/*

public static void main(String[] args) {
  String INPUT_DIRECTORY_BASE_PATH = args[0];
  String CONFIG_RELATIVE_PATH = args[1];
  String OUTPUT_DIRECTORY_BASE_PATH = args[2];

  List<AbstractModule> modules = new ArrayList<>(Arrays.<AbstractModule>asList(new BeamControlerModule()));

  Config config = ConfigUtils.loadConfig(INPUT_DIRECTORY_BASE_PATH + File.separator + CONFIG_RELATIVE_PATH,new BeamConfigGroup(), new BeamEventLoggerConfigGroup());
  ((BeamConfigGroup)config.getModules().get("beam")).customizeConfiguration(config,INPUT_DIRECTORY_BASE_PATH,CONFIG_RELATIVE_PATH,OUTPUT_DIRECTORY_BASE_PATH);

  com.google.inject.Injector injector = Injector.createInjector(config,AbstractModule.override(Collections.singleton(new AbstractModule() {
  @Override
  public void install() {
  install(new NewControlerModule());
  install(new ControllerCoreListenersModule());
  for (AbstractModule module : modules) {
  install(module);
}
}
}), AbstractModule.override(Arrays.asList(new ScenarioByConfigModule()),new BeamModule())));
  BeamController controller = injector.getInstance(BeamController.class);

  try {
  controller.getBeamServices().finalizeInitialization();
  controller.run(); // throws nothing but will not execute if init throws an error
} catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException
  | IllegalArgumentException | InvocationTargetException e) {
  e.printStackTrace();
}
}
*/