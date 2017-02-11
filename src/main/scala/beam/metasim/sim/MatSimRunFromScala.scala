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
import org.matsim.core.controler.listener.ControlerListener
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.{ScenarioByConfigModule, ScenarioUtils}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer


/**
  * Created by sfeygin on 1/30/17.
  */
object MatSimRunFromScala extends App{
  import net.codingwell.scalaguice.InjectorExtensions._
  val ConfigRelPath =  "test/input/beam/actors/"
  val ConfigFileName = "config.xml"
  val SimName = "actors"
  val OutputDirectoryBase = "test/output/beam/basicTests/actors"

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
        bindMobsim().to(classOf[QSim])
        addControlerListenerBinding().to(classOf[MetaSim])
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


