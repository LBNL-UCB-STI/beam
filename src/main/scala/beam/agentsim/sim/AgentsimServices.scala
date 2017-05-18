package beam.agentsim.sim

import java.io.File

import akka.actor.{ActorRef, ActorSystem}
import beam.agentsim.akkaguice.ActorInject
import beam.agentsim.config.{BeamConfig, ConfigModule}
import beam.agentsim.events.AgentsimEventsBus
import beam.agentsim.sim.modules.{AgentsimModule, BeamAgentModule}
import beam.agentsim.controler.corelisteners.BeamControllerCoreListenersModule
import beam.agentsim.controler.BeamControler
import beam.agentsim.utils.FileUtils
import beam.router.BoundingBox
import com.google.inject.{Inject, Injector, Singleton}
import com.typesafe.config.ConfigFactory
import glokka.Registry
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.controler._
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.scenario.{ScenarioByConfigModule, ScenarioUtils}
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer

object AgentsimServices {
  import beam.agentsim._
  import net.codingwell.scalaguice.InjectorExtensions._

  // Inject and use tsConfig instead here
  // Make implict to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
  val matsimConfig: org.matsim.core.config.Config = ConfigUtils.loadConfig(MatSimConfigLoc + MatSimConfigFilename)

  FileUtils.setConfigOutputFile(OutputDirectoryBase, SimName, matsimConfig)
  val scenario: Scenario = ScenarioUtils.loadScenario(matsimConfig)
  val injector: com.google.inject.Injector =
    org.matsim.core.controler.Injector.createInjector(matsimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        install(new NewControlerModule)
        install(new ScenarioByConfigModule)
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
  val beamConfig : BeamConfig = BeamConfig(ConfigFactory.parseFile(new File("src/main/resources/config-template.conf")).resolve())
  //TODO find a better way to inject the router, for now this is initilized inside Agentsim.notifyStartup
  var beamRouter : ActorRef = _
  var physSim: ActorRef = _
  var schedulerRef: ActorRef =_
  var taxiManager: ActorRef = _
  val bbox: BoundingBox = new BoundingBox()
  var popMap: Option[Map[Id[Person], Person]] = None
}

/**
  * Created by sfeygin on 2/11/17.
  */
@Singleton
case class AgentsimServices @Inject()(protected val injector: Injector) extends ActorInject {
  val matsimServices: MatsimServices = injector.getInstance(classOf[MatsimServices])
}
