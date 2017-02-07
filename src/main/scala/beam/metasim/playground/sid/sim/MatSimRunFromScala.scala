package beam.metasim.playground.sid.sim

import akka.actor.ActorSystem
import beam.metasim.playground.sid.agents.BeamAgent.PerformingActivity
import beam.metasim.playground.sid.agents.{DepartActivity, PersonAgent, StartDay}
import beam.metasim.playground.sid.akkaguice.GuiceAkkaExtension
import beam.metasim.playground.sid.sim.modules.{BeamAgentModule, BeamSimulationModule, ConfigModule}
import beam.metasim.playground.sid.utils.FileUtils
import beam.playground.metasim.controller.BeamController
import com.google.inject.{Guice, Key}
import net.codingwell.scalaguice.typeLiteral
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners._
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
        install(new NewControlerModule)
        install(new ScenarioByConfigModule)
        install(new ControlerDefaultsModule)
        install(new ControlerDefaultCoreListenersModule)
        install(new ConfigModule)
        install(new BeamSimulationModule)
        install(new BeamAgentModule)
      }
    }),new AbstractModule() {
      override def install(): Unit = {
        bind(classOf[ControlerI]).to(classOf[ControlerImpl]).asEagerSingleton()
      }
    }))
  val system = injector.instance[ActorSystem]
  val personAgent = system.actorOf((GuiceAkkaExtension(system).props(PersonAgent.name)))

  personAgent ! StartDay
  personAgent ! DepartActivity

  val controler = injector.instance[ControlerI]
  controler.run()

  system.terminate()


}


