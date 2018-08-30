package beam.router

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.collection.mutable

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  Address,
  Props,
  RelativeActorPath,
  RootActorPath,
  Stash
}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.{BeamServices, TransitInitializer}
import beam.sim.metrics.MetricsPrinter
import beam.router.r5.R5RoutingWorker
import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.collection.mutable

class BeamRouter(
  services: BeamServices,
  transportNetwork: TransportNetwork,
  network: Network,
  eventsManager: EventsManager,
  transitVehicles: Vehicles,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator,
  useLocalWorker: Boolean = true
) extends Actor
    with Stash
    with ActorLogging {
  type Worker = ActorRef
  type OriginalSender = ActorRef
  type WorkWithOriginalSender = (Any, OriginalSender)
  type WorkId = Int
  type TimeSent = ZonedDateTime

  val availableWorkWithOriginalSender: mutable.Queue[WorkWithOriginalSender] =
    mutable.Queue.empty[WorkWithOriginalSender]
  val availableWorkers: mutable.Set[Worker] = mutable.Set.empty[Worker]

  val outstandingWorkIdToOriginalSenderMap: mutable.Map[WorkId, OriginalSender] =
    mutable.Map.empty[WorkId, OriginalSender]

  val outstandingWorkIdToTimeSent: mutable.Map[WorkId, TimeSent] =
    mutable.Map.empty[WorkId, TimeSent]
  //TODO: Add actual request with who sent so can handle retry better
  //TODO: Implement timeouts using stored sending time
  //TODO: What is better for memory? Separate mutable maps or a custom object containing everything needed?

  // TODO Fix me!
  val servicePath = "/user/statsServiceProxy"

  //val cluster = Cluster(context.system)
  val servicePathElements = servicePath match {
    case RelativeActorPath(elements) => elements
    case _ =>
      throw new IllegalArgumentException(
        "servicePath [%s] is not a valid relative actor path" format servicePath
      )
  }

  var nodes = Set.empty[(Address, WorkerType)]
  implicit val ex = context.system.dispatcher
  log.info("BeamRouter: {}", self.path)

  //override def preStart(): Unit = {
  //  cluster.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent])
  //}

  //override def postStop(): Unit = {
  //  cluster.unsubscribe(self)
  //}

  val tick = "work-pull-tick"

  val tickTask =
    context.system.scheduler.schedule(10.seconds, 30.seconds, self, tick)(context.dispatcher)

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private val config = services.beamConfig.beam.routing

  if (useLocalWorker) {
    val localWorker = context.actorOf(
      R5RoutingWorker.props(
        services,
        transportNetwork,
        network,
        fareCalculator,
        tollCalculator,
        transitVehicles
      ),
      "router-worker"
    )
    nodes += ((localWorker.path.address, LocalWorker))
    //TODO: Add Deathwatch to remove node
  }

  private val metricsPrinter = context.actorOf(MetricsPrinter.props())
  private var numStopsNotFound = 0

  override def receive: PartialFunction[Any, Unit] = {
    case `tick` =>
      if (isWorkAndNoAvailableWorkers) notifyWorkersOfAvailableWork
      logExcessiveOutstandingWork
    case InitTransit(scheduler, id) =>
      // We have to send TransitInited as Broadcast because our R5RoutingWorker is stateful!
      val f = Future
        .sequence(
          nodes.filter(_._2 == ClusterWorker).map {
            case (address, _) =>
              context
                .actorSelection(RootActorPath(address) / servicePathElements)
                .resolveOne(10.seconds)
                .flatMap { serviceActor: ActorRef =>
                  log.info("Sending InitTransit to  {}", serviceActor)
                  serviceActor ? InitTransit(scheduler, id)
                }
          } + Future {
            val initializer = new TransitInitializer(services, transportNetwork, transitVehicles)
            val transits = initializer.initMap
            initDriverAgents(initializer, scheduler, transits)
            nodes.filter(_._2 == LocalWorker).map {
              case (address, _) => {
                val localWorker = Await
                  .result(context.actorSelection(RootActorPath(address)).resolveOne, 60.seconds)
                localWorker ! TransitInited(transits)
              }
            }
          }
        )
        .map { _ =>
          Success("success")
        }
      f.pipeTo(sender)
    /*case msg: UpdateTravelTime =>
      metricsPrinter ! Print(
        Seq(
          "cache-router-time",
          "noncache-router-time",
          "noncache-transit-router-time",
          "noncache-nontransit-router-time"
        ),
        Nil
      )

      routerWorker.forward(msg)*/
    case GetMatSimNetwork =>
      sender ! MATSimNetwork(network)
    case state: CurrentClusterState =>
      log.info("CurrentClusterState: {}", state)
      nodes = state.members.collect {
        case m if m.hasRole("compute") && m.status == MemberStatus.Up =>
          ((m.address, ClusterWorker))
      }
      if (isWorkAvailable) notifyWorkersOfAvailableWork
    case MemberUp(m) if m.hasRole("compute") =>
      log.info("MemberUp[compute]: {}", m)
      nodes += ((m.address, ClusterWorker))
      notifyNewWorkerIfWorkAvailable(m.address, receivePath = "MemberUp[compute]", ClusterWorker)
    case other: MemberEvent =>
      log.info("MemberEvent: {}", other)
      nodes -= ((other.member.address, ClusterWorker))
      removeUnavailableMemberFromAvailableWorkers(other.member, ClusterWorker)
    //Why is this a removal?
    case UnreachableMember(m) =>
      log.info("UnreachableMember: {}", m)
      nodes -= ((m.address, ClusterWorker))
      removeUnavailableMemberFromAvailableWorkers(m, ClusterWorker)
    case ReachableMember(m) if m.hasRole("compute") =>
      log.info("ReachableMember: {}", m)
      nodes += ((m.address, ClusterWorker))
      notifyNewWorkerIfWorkAvailable(
        m.address,
        receivePath = "ReachableMember[compute]",
        ClusterWorker
      )
    case GimmeWork =>
      val worker = context.sender
      if (!isWorkAvailable)
        availableWorkers.add(worker) //Request must have been delayed since no work, but will send when something comes in
      else {
        val (work, originalSender) = availableWorkWithOriginalSender.dequeue()
        sendWorkTo(worker, work, originalSender, receivePath = "GimmeWork")
      }
    case routingResp: RoutingResponse =>
      pipeResponseToOriginalSender(routingResp)
      logIfResponseTookExcessiveTime(routingResp)
    case work =>
      val originalSender = context.sender
      if (!isWorkAvailable) { //No existing work
        if (!isWorkerAvailable) {
          notifyWorkersOfAvailableWork
          availableWorkWithOriginalSender.enqueue((work, originalSender))
        } else {
          val worker: Worker = removeAndReturnFirstAvailableWorker
          sendWorkTo(worker, work, originalSender, "Receive CatchAll")
        }
      } else { //Use existing work first
        if (!isWorkerAvailable) notifyWorkersOfAvailableWork //Shouldn't need this but it should be relatively idempotent
        availableWorkWithOriginalSender.enqueue((work, originalSender))
      }
  }

  private def isWorkAvailable: Boolean = availableWorkWithOriginalSender.nonEmpty

  private def isWorkerAvailable: Boolean = availableWorkers.nonEmpty

  private def isWorkAndNoAvailableWorkers: Boolean =
    isWorkAvailable && !isWorkerAvailable

  private def notifyWorkersOfAvailableWork: Unit =
    nodes.foreach {
      case (workerAddress, workerType) => workerFrom(workerAddress, workerType) ! WorkAvailable
    }

  private def workerFrom(workerAddress: Address, workerType: WorkerType) = workerType match {
    case LocalWorker   => context.actorSelection(RootActorPath(workerAddress))
    case ClusterWorker => context.actorSelection(RootActorPath(workerAddress) / servicePathElements)
  }

  private def getCurrentTime: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)

  private def logExcessiveOutstandingWork = Future {
    val currentTime = getCurrentTime
    outstandingWorkIdToTimeSent.collect {
      case (workId: WorkId, timeSent: TimeSent) => {
        val secondsSinceSent = timeSent.until(currentTime, java.time.temporal.ChronoUnit.SECONDS)
        if (secondsSinceSent > 120)
          log.warning(
            "Haven't heard back from work ID '{}' for {} seconds.",
            workId,
            secondsSinceSent
          )
      }
    }
  }

  private def removeUnavailableMemberFromAvailableWorkers(
    member: Member,
    workerType: WorkerType
  ) = {
    val worker = Await.result(workerFrom(member.address, workerType).resolveOne, 60.seconds)
    if (availableWorkers.contains(worker)) { availableWorkers.remove(worker) }
    //TODO: If there is work outstanding then it needs handled
  }

  private def notifyNewWorkerIfWorkAvailable(
    workerAddress: => Address,
    receivePath: => String,
    workerType: => WorkerType
  ) = {
    if (isWorkAvailable) {
      val worker = workerFrom(workerAddress, workerType)
      log.debug("Sending WorkAvailable via {}: {}", receivePath, worker)
      worker ! WorkAvailable
    }
  }

  private def sendWorkTo(
    worker: Worker,
    work: Any,
    originalSender: OriginalSender,
    receivePath: => String
  ) = {
    work match {
      case routingRequest: RoutingRequest =>
        outstandingWorkIdToOriginalSenderMap.put(routingRequest.requestId, originalSender) //TODO: Add a central Id trait so can just match on that and combine logic
        outstandingWorkIdToTimeSent.put(routingRequest.requestId, getCurrentTime)
        worker ! work
      case embodyWithCurrentTravelTime: EmbodyWithCurrentTravelTime =>
        outstandingWorkIdToOriginalSenderMap.put(
          embodyWithCurrentTravelTime.id,
          originalSender
        )
        outstandingWorkIdToTimeSent.put(embodyWithCurrentTravelTime.id, getCurrentTime)
        worker ! work
      case otherWork =>
        log.warning(
          "Forwarding work via {} instead of telling because it isn't a handled type - {}",
          receivePath,
          work
        )
        worker.forward(work)
    }
  }

  private def pipeResponseToOriginalSender(routingResp: RoutingResponse) =
    outstandingWorkIdToOriginalSenderMap.remove(routingResp.requestId.get) match {
      case Some(originalSender) => originalSender ! routingResp
      case None =>
        log.error(
          "Received a RoutingResponse that does not match a tracked WorkId: {}",
          routingResp.requestId
        )
    }

  private def logIfResponseTookExcessiveTime(routingResp: RoutingResponse) =
    outstandingWorkIdToTimeSent.remove(routingResp.requestId.get) match {
      case Some(timeSent) =>
        val secondsSinceSent = timeSent.until(getCurrentTime, java.time.temporal.ChronoUnit.SECONDS)
        if (secondsSinceSent > 30)
          log.warning(
            "Took longer than 30 seconds to hear back from work id '{}' - {} seconds",
            routingResp.requestId,
            secondsSinceSent
          )
      case None => //No matching id. No need to log since this is more for analysis
    }

  private def removeAndReturnFirstAvailableWorker: Worker = {
    val worker = availableWorkers.head
    availableWorkers.remove(worker)
    worker
  }

  private def initDriverAgents(
    initializer: TransitInitializer,
    scheduler: ActorRef,
    transits: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])]
  ): Unit = {
    transits.foreach {
      case (tripVehId, (route, legs)) =>
        initializer.createTransitVehicle(tripVehId, route, legs).foreach { vehicle =>
          services.vehicles += (tripVehId -> vehicle)
          val transitDriverId =
            TransitDriverAgent.createAgentIdFromVehicleId(tripVehId)
          val transitDriverAgentProps = TransitDriverAgent.props(
            scheduler,
            services,
            transportNetwork,
            eventsManager,
            transitDriverId,
            vehicle,
            legs
          )
          val transitDriver =
            context.actorOf(transitDriverAgentProps, transitDriverId.toString)
          scheduler ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)
        }
    }
  }
}

object BeamRouter {
  type Location = Coord

  case class InitTransit(scheduler: ActorRef, id: UUID = UUID.randomUUID())

  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])

  case class EmbodyWithCurrentTravelTime(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    id: Int = this.hashCode()
  )

  case class UpdateTravelTime(travelTime: TravelTime)

  case class R5Network(transportNetwork: TransportNetwork)

  case object GetTravelTime

  case class MATSimNetwork(network: Network)

  case object GetMatSimNetwork

  /**
    * It is use to represent a request object
    *
    * @param origin                 start/from location of the route
    * @param destination            end/to location of the route
    * @param departureTime          time in seconds from base midnight
    * @param transitModes           what transit modes should be considered
    * @param streetVehicles         what vehicles should be considered in route calc
    * @param streetVehiclesUseIntermodalUse boolean (default true), if false, the vehicles considered for use on egress
    */
  case class RoutingRequest(
    origin: Location,
    destination: Location,
    departureTime: BeamTime,
    transitModes: Seq[BeamMode],
    streetVehicles: Seq[StreetVehicle],
    streetVehiclesUseIntermodalUse: IntermodalUse = Access,
    mustParkAtEnd: Boolean = false
  ) {
    lazy val requestId: Int = this.hashCode()
  }

  sealed trait IntermodalUse
  case object Access extends IntermodalUse
  case object Egress extends IntermodalUse
  case object AccessAndEgress extends IntermodalUse

  /**
    * Message to respond a plan against a particular router request
    *
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(
    itineraries: Seq[EmbodiedBeamTrip],
    requestId: Option[Int] = None
  ) {
    lazy val responseId: Int = this.hashCode()
  }

  def props(
    beamServices: BeamServices,
    transportNetwork: TransportNetwork,
    network: Network,
    eventsManager: EventsManager,
    transitVehicles: Vehicles,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator
  ) =
    Props(
      new BeamRouter(
        beamServices,
        transportNetwork,
        network,
        eventsManager,
        transitVehicles,
        fareCalculator,
        tollCalculator
      )
    )

  sealed trait WorkMessage
  case object GimmeWork extends WorkMessage
  case object WorkAvailable extends WorkMessage

  sealed trait WorkerType
  case object LocalWorker extends WorkerType
  case object ClusterWorker extends WorkerType
}
