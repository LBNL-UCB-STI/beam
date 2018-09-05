package beam.router
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.routing.{Broadcast, FromConfig}
import beam.router.BeamRouter._
import beam.router.r5.R5RoutingWorker
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.Config
import org.matsim.api.core.v01.network.Network
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.utils.reflection.ReflectionUtils
import beam.utils.{DateUtils, FileUtils, LoggingUtil}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.ControlerI
import org.matsim.api.core.v01.population.Person
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.BeamRouter.TransitInited
import akka.routing.Broadcast
import com.conveyal.r5.streets._
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile._
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.router.r5.NetworkCoordinator
import beam.router.BeamRouter._
import com.google.inject.Injector
import org.matsim.vehicles.{Vehicle, Vehicles}

class ClusterWorkerRouter(config: Config) extends Actor with ActorLogging {

  // This router is used both with lookup and deploy of routees. If you
  // have a router with only lookup of routees you can use Props.empty
  // instead of Props[StatsWorker.class].
  log.info("TEST")

  val workerRouter = try {
    context.actorOf(
      FromConfig.props(Props(classOf[R5RoutingWorker], config)),
      name = "workerRouter"
    )
  } catch {
    case x => log.info(x.toString); throw x
  }
  def getNameAndHashCode: String = s"ClusterWorkerRouter[${hashCode()}], Path: `${self.path}`"
  log.info("{} inited. workerRouter => {}", getNameAndHashCode, workerRouter)

  def receive = {
    // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
    case transitInited: TransitInited =>
      log.info("{} Sending Broadcast", getNameAndHashCode)
      workerRouter.tell(Broadcast(transitInited), sender())
    // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
    case initTransit: InitTransit =>
      log.info("{} Sending Broadcast", getNameAndHashCode)
      workerRouter.tell(Broadcast(initTransit), sender())

    case other =>
      log.debug("{} received {}", getNameAndHashCode, other)
      workerRouter.forward(other)
  }
  /*
  @transient
  lazy val workerParameters = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
    LoggingUtil.createFileLogger(outputDirectory)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = new NetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    scenario.setNetwork(networkCoordinator.network)
    val network = networkCoordinator.network
    val transportNetwork = networkCoordinator.transportNetwork
    val beamServices: BeamServices = new BeamServices {
      override lazy val controler: ControlerI = ???
      override var beamConfig: BeamConfig = BeamConfig(config)
      override lazy val registry: ActorRef = throw new Exception("???")
      override lazy val geo: GeoUtils = new GeoUtilsImpl(this)
      override var modeChoiceCalculatorFactory
        : HouseholdActor.AttributesOfIndividual => ModeChoiceCalculator = _
      override val dates: DateUtils = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
      override var beamRouter: ActorRef = null
      override val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap[Id[Person], ActorRef]()
      override val vehicles: TrieMap[Id[Vehicle], BeamVehicle] = TrieMap[Id[Vehicle], BeamVehicle]()
      override def startNewIteration: Unit = throw new Exception("???")
      override protected def injector: Injector = throw new Exception("???")
      override def matsimServices_=(x$1: org.matsim.core.controler.MatsimServices): Unit = ???
      override def rideHailIterationHistoryActor_=(x$1: akka.actor.ActorRef): Unit = ???
      override val tazTreeMap: beam.agentsim.infrastructure.TAZTreeMap = beam.sim.BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.file)
      override def matsimServices: org.matsim.core.controler.MatsimServices = ???
      override def rideHailIterationHistoryActor: akka.actor.ActorRef = ???
    }

    val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
    val tollCalculator = new TollCalculator(beamConfig.beam.routing.r5.directory)
    WorkerParameters(
      beamServices,
      transportNetwork,
      network,
      fareCalculator,
      tollCalculator,
      scenario.getTransitVehicles
    )
  }*/
}
case class WorkerParameters(
  beamServices: BeamServices,
  transportNetwork: TransportNetwork,
  network: Network,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator,
  transitVehicles: Vehicles
)
