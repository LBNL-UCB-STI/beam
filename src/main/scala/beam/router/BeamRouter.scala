package beam.router

import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  Address,
  Cancellable,
  ExtendedActorSystem,
  Props,
  RelativeActorPath,
  RootActorPath,
  Stash
}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.gtfs.FareCalculator
import beam.router.model._
import beam.router.osm.TollCalculator
import beam.router.r5.RouteDumper
import beam.sim.common.GeoUtils
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.{DateUtils, IdGeneratorImpl, NetworkHelper}
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.transit.TransportNetwork
import com.romix.akka.serialization.kryo.KryoSerializer
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Try

class BeamRouter(
  beamScenario: BeamScenario,
  transportNetwork: TransportNetwork,
  network: Network,
  networkHelper: NetworkHelper,
  geo: GeoUtils,
  scenario: Scenario,
  transitVehicles: Vehicles,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator,
  eventsManager: EventsManager
) extends Actor
    with Stash
    with ActorLogging {
  type Worker = ActorRef
  type OriginalSender = ActorRef
  type WorkWithOriginalSender = (Any, OriginalSender)
  type WorkId = Int
  type TimeSent = ZonedDateTime

  val clearRoutedOutstandingWorkEnabled: Boolean = beamScenario.beamConfig.beam.debug.clearRoutedOutstandingWorkEnabled

  val secondsToWaitToClearRoutedOutstandingWork: Int =
    beamScenario.beamConfig.beam.debug.secondsToWaitToClearRoutedOutstandingWork

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

  val clusterOption: Option[Cluster] =
    if (beamScenario.beamConfig.beam.cluster.enabled) Some(Cluster(context.system)) else None

  val servicePathElements: immutable.Seq[String] = servicePath match {
    case RelativeActorPath(elements) => elements
    case _ =>
      throw new IllegalArgumentException(
        "servicePath [%s] is not a valid relative actor path" format servicePath
      )
  }

  var remoteNodes = Set.empty[Address]
  var localNodes = Set.empty[ActorRef]
  implicit val ex: ExecutionContextExecutor = context.system.dispatcher
  log.info("BeamRouter: {}", self.path)

  override def preStart(): Unit = {
    clusterOption.foreach(_.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent]))
  }

  override def postStop(): Unit = {
    clusterOption.foreach(_.unsubscribe(self))
    tickTask.cancel()
  }

  val tick = "work-pull-tick"

  val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(10.seconds, 30.seconds, self, tick)(context.dispatcher)

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  if (beamScenario.beamConfig.beam.useLocalWorker) {
    val localWorker = context.actorOf(
      RoutingWorker.props(
        beamScenario,
        transportNetwork,
        networkHelper,
        fareCalculator,
        tollCalculator
      ),
      "router-worker"
    )
    localNodes += localWorker
    //TODO: Add Deathwatch to remove node
  }

  private var traveTimeOpt: Option[TravelTime] = None

  val kryoSerializer = new KryoSerializer(context.system.asInstanceOf[ExtendedActorSystem])

  private val updateTravelTimeTimeout: Timeout = Timeout(3, TimeUnit.MINUTES)

  private var currentIteration: Int = 0

  override def receive: PartialFunction[Any, Unit] = {
    case IterationStartsMessage(iteration) =>
      currentIteration = iteration
    case `tick` =>
      if (isWorkAndNoAvailableWorkers) notifyWorkersOfAvailableWork()
      logExcessiveOutstandingWorkAndClearIfEnabledAndOver
    case t: TryToSerialize =>
      if (log.isDebugEnabled) {
        val byteArray = kryoSerializer.toBinary(t)
        log.debug(
          "TryToSerialize size in bytes: {}, MBytes: {}",
          byteArray.length,
          byteArray.length.toDouble / 1024 / 1024
        )
      }
    case msg: UpdateTravelTimeLocal =>
      traveTimeOpt = Some(msg.travelTime)
      localNodes.foreach(_.forward(msg))
    case UpdateTravelTimeRemote(map) =>
      val nodes = remoteNodes
      nodes.foreach { address =>
        resolveAddressBlocking(address).foreach { serviceActor =>
          log.info("Sending UpdateTravelTime_v2 to  {}", serviceActor)
          serviceActor.ask(UpdateTravelTimeRemote(map))(updateTravelTimeTimeout)
        }
      }
    case GetMatSimNetwork =>
      sender ! MATSimNetwork(network)
    case GetTravelTime =>
      traveTimeOpt match {
        case Some(travelTime) => sender ! UpdateTravelTimeLocal(travelTime)
        case None             => sender ! R5Network(transportNetwork)
      }
    case state: CurrentClusterState =>
      log.info("CurrentClusterState: {}", state)
      remoteNodes = state.members.collect {
        case m if m.hasRole("compute") && m.status == MemberStatus.Up => m.address
      }
      if (isWorkAvailable) notifyWorkersOfAvailableWork()
    case MemberUp(m) if m.hasRole("compute") =>
      log.info("MemberUp[compute]: {}", m)
      remoteNodes += m.address
      notifyNewWorkerIfWorkAvailable(m.address, receivePath = "MemberUp[compute]")
    case other: MemberEvent =>
      log.info("MemberEvent: {}", other)
      other match {
        case MemberExited(_) | MemberRemoved(_, _) =>
          remoteNodes -= other.member.address
          removeUnavailableMemberFromAvailableWorkers(other.member)
        case _ =>
      }
    //Why is this a removal?
    case UnreachableMember(m) =>
      log.info("UnreachableMember: {}", m)
      remoteNodes -= m.address
      removeUnavailableMemberFromAvailableWorkers(m)
    case ReachableMember(m) if m.hasRole("compute") =>
      log.info("ReachableMember: {}", m)
      remoteNodes += m.address
      notifyNewWorkerIfWorkAvailable(
        m.address,
        receivePath = "ReachableMember[compute]"
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
      if (shouldWriteR5Routes(currentIteration))
        eventsManager.processEvent(RouteDumper.RoutingResponseEvent(routingResp))

      pipeResponseToOriginalSender(routingResp)
      logIfResponseTookExcessiveTime(routingResp.requestId)
    case routingFailure: RoutingFailure =>
      pipeTransformedFailureToOriginalSender(routingFailure)
      logIfResponseTookExcessiveTime(routingFailure.requestId)
    case ClearRoutedWorkerTracker(workIdToClear) =>
      //TODO: Maybe do this for all tracker removals?
      removeOutstandingWorkBy(workIdToClear)

    case work =>
      processByEventsManagerIfNeeded(work)
      val originalSender = context.sender
      if (!isWorkAvailable) { //No existing work
        if (!isWorkerAvailable) {
          notifyWorkersOfAvailableWork()
          availableWorkWithOriginalSender.enqueue((work, originalSender))
        } else {
          val worker: Worker = removeAndReturnFirstAvailableWorker()
          sendWorkTo(worker, work, originalSender, "Receive CatchAll")
        }
      } else { //Use existing work first
        if (!isWorkerAvailable) notifyWorkersOfAvailableWork() //Shouldn't need this but it should be relatively idempotent
        availableWorkWithOriginalSender.enqueue((work, originalSender))
      }
  }

  private def processByEventsManagerIfNeeded(work: Any): Unit = {
    work match {
      case e: EmbodyWithCurrentTravelTime if (shouldWriteR5Routes(currentIteration)) =>
        eventsManager.processEvent(RouteDumper.EmbodyWithCurrentTravelTimeEvent(e))
      case req: RoutingRequest =>
        eventsManager.processEvent(RouteDumper.RoutingRequestEvent(req))
      case _ =>
    }
  }

  private def isWorkAvailable: Boolean = availableWorkWithOriginalSender.nonEmpty

  private def isWorkerAvailable: Boolean = availableWorkers.nonEmpty

  private def isWorkAndNoAvailableWorkers: Boolean =
    isWorkAvailable && !isWorkerAvailable

  private def notifyWorkersOfAvailableWork(): Unit = {
    remoteNodes.foreach(workerAddress => workerFrom(workerAddress) ! WorkAvailable)
    localNodes.foreach(_ ! WorkAvailable)
  }

  private def workerFrom(workerAddress: Address) =
    context.actorSelection(RootActorPath(workerAddress) / servicePathElements)

  private def getCurrentTime: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)

  private def logExcessiveOutstandingWorkAndClearIfEnabledAndOver = Future {
    val currentTime = getCurrentTime
    outstandingWorkIdToTimeSent.collect {
      case (workId: WorkId, timeSent: TimeSent) =>
        val secondsSinceSent = timeSent.until(currentTime, java.time.temporal.ChronoUnit.SECONDS)
        if (clearRoutedOutstandingWorkEnabled && secondsSinceSent > secondsToWaitToClearRoutedOutstandingWork) {
          //TODO: Can the logs be combined?
          log.warning(
            "Haven't heard back from work ID '{}' for {} seconds. " +
            "This is over the configured threshold {}, so submitting to be cleared.",
            workId,
            secondsSinceSent,
            secondsToWaitToClearRoutedOutstandingWork
          )
          self ! ClearRoutedWorkerTracker(workIdToClear = workId)
        } else if (secondsSinceSent > 120)
          log.warning(
            "Haven't heard back from work ID '{}' for {} seconds.",
            workId,
            secondsSinceSent
          )
    }
  }

  private def removeUnavailableMemberFromAvailableWorkers(
    member: Member
  ): Unit = {
    try {
      val worker = Await.result(workerFrom(member.address).resolveOne, 60.seconds)
      if (availableWorkers.contains(worker)) { availableWorkers.remove(worker) }
      //TODO: If there is work outstanding then it needs handled
    } catch {
      case ex: Throwable =>
        log.error(ex, s"removeUnavailableMemberFromAvailableWorkers failed with: ${ex.getMessage}")
    }
  }

  private def notifyNewWorkerIfWorkAvailable(
    workerAddress: => Address,
    receivePath: => String
  ): Unit = {
    if (isWorkAvailable) {
      val worker = workerFrom(workerAddress)
      log.debug("Sending WorkAvailable via {}: {}", receivePath, worker)
      worker ! WorkAvailable
    }
  }

  private def sendWorkTo(
    worker: Worker,
    work: Any,
    originalSender: OriginalSender,
    receivePath: => String
  ): Unit = {
    work match {
      case routingRequest: RoutingRequest =>
        outstandingWorkIdToOriginalSenderMap.put(routingRequest.requestId, originalSender) //TODO: Add a central Id trait so can just match on that and combine logic
        outstandingWorkIdToTimeSent.put(routingRequest.requestId, getCurrentTime)
        worker ! work
      case embodyWithCurrentTravelTime: EmbodyWithCurrentTravelTime =>
        outstandingWorkIdToOriginalSenderMap.put(
          embodyWithCurrentTravelTime.requestId,
          originalSender
        )
        outstandingWorkIdToTimeSent.put(embodyWithCurrentTravelTime.requestId, getCurrentTime)
        worker ! work
      case _ =>
        log.warning(
          "Forwarding work via {} instead of telling because it isn't a handled type - {}",
          receivePath,
          work
        )
        worker.forward(work)
    }
  }

  private def pipeResponseToOriginalSender(routingResp: RoutingResponse): Unit =
    outstandingWorkIdToOriginalSenderMap.remove(routingResp.requestId) match {
      case Some(originalSender) => originalSender ! routingResp
      case None =>
        log.error(
          "Received a RoutingResponse that does not match a tracked WorkId: {}",
          routingResp.requestId
        )
    }

  private def pipeTransformedFailureToOriginalSender(routingFailure: RoutingFailure): Unit =
    outstandingWorkIdToOriginalSenderMap.remove(routingFailure.requestId) match {
      case Some(originalSender) => originalSender ! Failure(routingFailure.cause)
      case None =>
        log.error(
          "Received a RoutingFailure that does not match a tracked WorkId: {}",
          routingFailure.requestId
        )
    }

  private def logIfResponseTookExcessiveTime(requestId: Int): Unit =
    outstandingWorkIdToTimeSent.remove(requestId) match {
      case Some(timeSent) =>
        val secondsSinceSent = timeSent.until(getCurrentTime, java.time.temporal.ChronoUnit.SECONDS)
        if (secondsSinceSent > 30)
          log.warning(
            "Took longer than 30 seconds to hear back from work id '{}' - {} seconds",
            requestId,
            secondsSinceSent
          )
      case None => //No matching id. No need to log since this is more for analysis
    }

  private def removeAndReturnFirstAvailableWorker(): Worker = {
    val worker = availableWorkers.head
    availableWorkers.remove(worker)
    worker
  }

  private def removeOutstandingWorkBy(workId: Int): Unit = {
    outstandingWorkIdToOriginalSenderMap.remove(workId)
    outstandingWorkIdToTimeSent.remove(workId)
  }

  private def resolveAddressBlocking(addr: Address, d: FiniteDuration = 60.seconds): Option[ActorRef] = {
    Await.result(resolveAddress(addr, d), d)
  }

  private def resolveAddress(addr: Address, duration: FiniteDuration = 60.seconds): Future[Option[ActorRef]] = {
    workerFrom(addr)
      .resolveOne(duration)
      .map { r =>
        Option(r)
      }
      .recover {
        case t: Throwable =>
          log.error(t, "Can't resolve '{}': {}", addr, t.getMessage)
          None
      }
  }

  def shouldWriteR5Routes(iteration: Int): Boolean = {
    val writeInterval = beamScenario.beamConfig.beam.outputs.writeR5RoutesInterval
    writeInterval > 0 && iteration % writeInterval == 0
  }
}

object BeamRouter {
  type Location = Coord

  case class ClearRoutedWorkerTracker(workIdToClear: Int)
  case class EmbodyWithCurrentTravelTime(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    requestId: Int = IdGeneratorImpl.nextId
  )
  case class UpdateTravelTimeLocal(travelTime: TravelTime)
  case class R5Network(transportNetwork: TransportNetwork)
  case object GetTravelTime
  case class MATSimNetwork(network: Network)
  case object GetMatSimNetwork

  case class TryToSerialize(obj: Object)
  case class UpdateTravelTimeRemote(linkIdToTravelTimePerHour: java.util.Map[String, Array[Double]])

  /**
    * It is use to represent a request object
    *
    * @param originUTM                 start/from location of the route
    * @param destinationUTM            end/to location of the route
    * @param departureTime          time in seconds from base midnight
    * @param streetVehicles         what vehicles should be considered in route calc
    * @param streetVehiclesUseIntermodalUse boolean (default true), if false, the vehicles considered for use on egress
    */
  case class RoutingRequest(
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    withTransit: Boolean,
    personId: Option[Id[Person]] = None,
    streetVehicles: IndexedSeq[StreetVehicle],
    attributesOfIndividual: Option[AttributesOfIndividual] = None,
    streetVehiclesUseIntermodalUse: IntermodalUse = Access,
    requestId: Int = IdGeneratorImpl.nextId
  )(implicit fileName: sourcecode.FileName, fullName: sourcecode.FullName, line: sourcecode.Line) {
    lazy val timeValueOfMoney
      : Double = attributesOfIndividual.fold(360.0)(3600.0 / _.valueOfTime) // 360 seconds per Dollar, i.e. 10$/h value of travel time savings

    val initiatedFrom: String = s"${fileName.value}:${line.value} ${fullName.value}"
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
    requestId: Int,
    request: Option[RoutingRequest],
    isEmbodyWithCurrentTravelTime: Boolean
  )

  case class RoutingFailure(cause: Throwable, requestId: Int)

  object RoutingResponse {

    val dummyRoutingResponse: Some[RoutingResponse] = Some(
      RoutingResponse(Vector(), IdGeneratorImpl.nextId, None, isEmbodyWithCurrentTravelTime = false)
    )
  }

  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    network: Network,
    networkHelper: NetworkHelper,
    geo: GeoUtils,
    scenario: Scenario,
    transitVehicles: Vehicles,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator,
    eventsManager: EventsManager
  ): Props = {
    checkForConsistentTimeZoneOffsets(beamScenario.dates, transportNetwork)

    Props(
      new BeamRouter(
        beamScenario,
        transportNetwork,
        network,
        networkHelper,
        geo,
        scenario,
        transitVehicles,
        fareCalculator,
        tollCalculator,
        eventsManager
      )
    )
  }

  def linkIdsToEmbodyRequest(
    linkIds: IndexedSeq[Int],
    vehicle: StreetVehicle,
    departTime: Int,
    mode: BeamMode,
    beamServices: BeamServices,
    originUTM: Coord,
    destinationUTM: Coord,
    requestIdOpt: Option[Int] = None
  ): EmbodyWithCurrentTravelTime = {
    val leg = BeamLeg(
      departTime,
      mode,
      1,
      BeamPath(
        linkIds,
        Vector.empty,
        None,
        beamServices.geo.utm2Wgs(SpaceTime(originUTM, departTime)),
        beamServices.geo.utm2Wgs(SpaceTime(destinationUTM, departTime + 1)),
        linkIds.map(beamServices.networkHelper.getLinkUnsafe(_).getLength).sum
      )
    )
    requestIdOpt match {
      case Some(reqId) =>
        EmbodyWithCurrentTravelTime(
          leg,
          vehicle.id,
          vehicle.vehicleTypeId,
          reqId
        )
      case None =>
        EmbodyWithCurrentTravelTime(
          leg,
          vehicle.id,
          vehicle.vehicleTypeId
        )
    }
  }

  def matsimLegToEmbodyRequest(
    route: NetworkRoute,
    vehicle: StreetVehicle,
    departTime: Int,
    mode: BeamMode,
    beamServices: BeamServices,
    origin: Coord,
    destination: Coord
  ): EmbodyWithCurrentTravelTime = {
    val linkIds = new ArrayBuffer[Int](2 + route.getLinkIds.size())
    linkIds += route.getStartLinkId.toString.toInt
    route.getLinkIds.asScala.foreach { id =>
      linkIds += id.toString.toInt
    }
    linkIds += route.getEndLinkId.toString.toInt
    // TODO Why don't we send `route.getTravelTime.toInt` as a travel time??
    val leg = BeamLeg(
      departTime,
      mode,
      1,
      BeamPath(
        linkIds,
        Vector.empty,
        None,
        beamServices.geo.utm2Wgs(SpaceTime(origin, departTime)),
        beamServices.geo.utm2Wgs(SpaceTime(destination, departTime + 1)),
        RouteUtils.calcDistance(route, 1.0, 1.0, beamServices.matsimServices.getScenario.getNetwork)
      )
    )
    EmbodyWithCurrentTravelTime(
      leg,
      vehicle.id,
      vehicle.vehicleTypeId
    )
  }

  def checkForConsistentTimeZoneOffsets(dates: DateUtils, transportNetwork: TransportNetwork): Unit = {
    if (dates.zonedBaseDateTime.getOffset != transportNetwork.getTimeZone.getRules.getOffset(
          dates.localBaseDateTime
        )) {
      throw new RuntimeException(
        "Time Zone Mismatch\n\n" +
        s"\tZone offset inferred by R5: ${transportNetwork.getTimeZone.getRules.getOffset(dates.localBaseDateTime)}\n" +
        s"\tZone offset specified in Beam config file: ${dates.zonedBaseDateTime.getOffset}\n\n" +
        "Detailed Explanation:\n\n" +
        "There is a subtle requirement in BEAM related to timezones that is easy to miss and cause problems.\n\n" +
        "BEAM uses the R5 router, which was designed as a stand-alone service either for doing accessibility analysis or as a point to point trip planner. R5 was designed with public transit at the top of the developers’ minds, so they infer the time zone of the region being modeled from the 'timezone' field in the 'agency.txt' file in the first GTFS data archive that is parsed during the network building process.\n\n" +
        "Therefore, if no GTFS data is provided to R5, it cannot infer the locate timezone and it then assumes UTC.\n\n" +
        "Meanwhile, there is a parameter in beam, 'beam.routing.baseDate' that is used to ensure that routing requests to R5 are send with the appropriate timestamp. This allows you to run BEAM using any sub-schedule in your GTFS archive. I.e. if your base date is a weekday, R5 will use the weekday schedules for transit, if it’s a weekend day, then the weekend schedules will be used.\n\n" +
        "The time zone in the baseDate parameter (e.g. for PST one might use '2016-10-17T00:00:00-07:00') must match the time zone in the GTFS archive(s) provided to R5.\n\n" +
        "As a default, we provide a 'dummy' GTFS data archive that is literally empty of any transit schedules, but is still a valid GTFS archive. This archive happens to have a time zone of Los Angeles. You can download a copy of this archive here:\n\n" +
        "https://www.dropbox.com/s/2tfbhxuvmep7wf7/dummy.zip?dl=1\n\n" +
        "But in general, if you use your own GTFS data for your region, then you may need to change this baseDate parameter to reflect the local time zone there. Look for the 'timezone' field in the 'agency.txt' data file in the GTFS archive.\n\n" +
        "The date specified by the baseDate parameter must fall within the schedule of all GTFS archives included in the R5 sub-directory. See the 'calendar.txt' data file in the GTFS archive and make sure your baseDate is within the 'start_date' and 'end_date' fields folder across all GTFS inputs. If this is not the case, you can either change baseDate or you can change the GTFS data, expanding the date ranges… the particular dates chosen are arbitrary and will have no other impact on the simulation results.\n\n" +
        "One more word of caution. If you make changes to GTFS data, then make sure your properly zip the data back into an archive. You do this by selecting all of the individual text files and then right-click-compress. Do not compress the folder containing the GTFS files, if you do this, R5 will fail to read your data and will do so without any warning or errors.\n\n" +
        "Finally, any time you make a changes to either the GTFS inputs or the OSM network inputs, then you need to delete the file 'network.dat' under the 'r5' sub-directory. This will signal to the R5 library to re-build the network."
      )
    }

  }

  sealed trait WorkMessage
  case object GimmeWork extends WorkMessage
  case object WorkAvailable extends WorkMessage

  def oneSecondTravelTime(a: Double, b: Int, c: StreetMode) = 1.0

  case class IterationStartsMessage(iteration: Int)
}
