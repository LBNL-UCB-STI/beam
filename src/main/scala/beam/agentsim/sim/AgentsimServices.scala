package beam.agentsim.sim

import akka.actor.{ActorRef, ActorSystem}
import beam.agentsim.akkaguice.ActorInject
import beam.agentsim.config.{BeamConfig, ConfigModule}
import beam.agentsim.controler.BeamControler
import beam.agentsim.controler.corelisteners.BeamControllerCoreListenersModule
import beam.agentsim.events.AgentsimEventsBus
import beam.agentsim.routing.BoundingBox
import beam.agentsim.sim.modules.{AgentsimModule, BeamAgentModule}
import beam.agentsim.utils.FileUtils
import com.google.inject.{Inject, Injector, Singleton}
import com.typesafe.config.ConfigFactory
import glokka.Registry
import org.matsim.api.core.v01.Scenario
import org.matsim.core.controler._
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.scenario.{ScenarioByInstanceModule, ScenarioUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object AgentsimServices {
  import beam.agentsim._
  import net.codingwell.scalaguice.InjectorExtensions._

  // Inject and use tsConfig instead here
  // Make implicit to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
  FileUtils.setConfigOutputFile(ConfigModule.beamConfig.beam.outputs.outputDirectory, SimName, ConfigModule.matSimConfig)
  val injector: com.google.inject.Injector =
    org.matsim.core.controler.Injector.createInjector(ConfigModule.matSimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        val scenario = ScenarioUtils.loadScenario(ConfigModule.matSimConfig)
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
        addControlerListenerBinding().to(classOf[Agentsim])
        bind(classOf[ControlerI]).to(classOf[BeamControler]).asEagerSingleton()
      }
    }))

  val controler: ControlerI = injector.instance[ControlerI]
  val agentSimEventsBus = new AgentsimEventsBus
  val registry: ActorRef = Registry.start(injector.getInstance(classOf[ActorSystem]), "actor-registry")

  //TODO find a better way to inject the router, for now this is initilized inside Agentsim.notifyStartup
  var beamRouter : ActorRef = _
  var schedulerRef: ActorRef =_
  var taxiManager: ActorRef = _
  val bbox: BoundingBox = new BoundingBox()

}

/**
  * Created by sfeygin on 2/11/17.
  */
@Singleton
case class AgentsimServices @Inject()(protected val injector: Injector, beamConfig: BeamConfig) extends ActorInject {
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
}
