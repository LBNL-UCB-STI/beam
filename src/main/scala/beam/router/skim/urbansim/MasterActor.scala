package beam.router.skim.urbansim

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props, Terminated}
import beam.agentsim.infrastructure.geozone.GeoIndex
import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.{ActivitySimPathType, ActivitySimSkimmer}
import beam.router.skim.core.AbstractSkimmer
import beam.router.skim.urbansim.MasterActor.Request.Monitor
import beam.router.skim.urbansim.MasterActor.Response.PopulatedSkimmer
import beam.router.skim.urbansim.MasterActor.{Request, Response}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.matsim.api.core.v01.Coord

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class MasterActor(
  val abstractSkimmer: AbstractSkimmer,
  val odRequester: ODRequester,
  val requestTimes: Seq[Int],
  rawODs: Array[(GeoIndex, GeoIndex)],
  val transitModeCategories: Seq[TransitModeCategory] = Seq.empty,
  val generateReturnTrips: Boolean = false,
  val bifurcateTolls: Boolean = false,
  val requestedParallelism: Int = 0 // 0 = auto-scale (80% of CPUs), >0 = use exact number
) extends Actor
    with ActorLogging {

  // Filter out same-origin pairs (src == dst) - no point routing to same location
  private val ODs: Array[(GeoIndex, GeoIndex)] = rawODs.filter { case (src, dst) => src != dst }
  private val skippedSameOriginPairs: Int = rawODs.length - ODs.length

  override def postStop(): Unit = {
    logStat()
    log.info(s"Stopping $self")
    maybeMonitor.foreach(_.cancel())
  }

  private val batchSize: Int = 500
  private val availableProcessors: Int = Runtime.getRuntime.availableProcessors()

  // If parallelism is explicitly requested (>0), use that; otherwise auto-scale to 80% of CPUs
  private val initialWorkers: Int = if (requestedParallelism > 0) {
    Math.min(requestedParallelism, availableProcessors)
  } else {
    Math.max(1, (availableProcessors * 0.8).toInt)
  }

  // Determine if we're using enhanced mode in stage 1 (with transit mode categories)
  private val useEnhancedMode: Boolean = transitModeCategories.nonEmpty

  // For enhanced mode, we pre-generate all stage 1 (outbound) work items
  private val workItems: Array[ODWorkItem] = if (useEnhancedMode) {
    generateWorkItems()
  } else {
    Array.empty
  }

  // Two-stage routing state
  // Stage 1: Outbound trips across all time periods
  // Stage 2: Return trips across all time periods with candidate parking locations
  private var stage: Int = 1
  private val stage1TotalRequests: Int = if (useEnhancedMode) {
    workItems.length
  } else {
    ODs.length * requestTimes.length
  }
  private var stage2TotalRequests: Int = 0
  private var maxRequestsNumber: Int = stage1TotalRequests

  // Accumulated parking locations per outbound OD pair: (outboundSrc, outboundDst) -> Set[Coord]
  private val outboundStationParkingSets: scala.collection.mutable.Map[(GeoIndex, GeoIndex), scala.collection.mutable.Set[Coord]] =
    scala.collection.mutable.Map.empty

  // Tolled car ODs recorded in Stage 1 for toll-avoidance routing in Stage 2
  private val tolledCarODs: scala.collection.mutable.Set[(GeoIndex, GeoIndex, Int)] =
    scala.collection.mutable.Set.empty

  // Stage 2 return work items
  private var returnWorkItems: Array[ODWorkItem] = Array.empty
  private var currentReturnWorkItemIdx: Int = 0

  // Current position in the work items array (for enhanced mode stage 1)
  private var currentWorkItemIdx: Int = 0

  // Legacy iteration state (for stage 1 without transit mode categories)
  private var currentIdx: Int = 0
  private var currentTime: Int = 0

  log.info(
    s"Stage 1 initialized | OD pairs: ${ODs.length} (skipped $skippedSameOriginPairs same-origin pairs), " +
    s"request time entries: ${requestTimes.length}, transit categories: ${transitModeCategories.size}, " +
    s"generateReturnTrips: $generateReturnTrips, bifurcateTolls: $bifurcateTolls, stage 1 work items: $stage1TotalRequests, initialWorkers: $initialWorkers"
  )

  /**
    * Generate all outbound work items for enhanced mode stage 1.
    */
  private def generateWorkItems(): Array[ODWorkItem] = {
    val categories: Seq[Option[TransitModeCategory]] = if (transitModeCategories.nonEmpty) {
      transitModeCategories.map(Some(_))
    } else {
      Seq(None)
    }

    val items = for {
      (src, dst) <- ODs
      time       <- requestTimes
      category   <- categories
    } yield ODWorkItem(src, dst, time, category, TripDirection.Outbound)

    items.toArray
  }

  private var workers: Set[ActorRef] = Set.empty
  private var workerToEc: Map[ActorRef, ExecutionContextExecutorService] = Map.empty
  private var workersToRemove: Set[ActorRef] = Set.empty

  private var replyToWhenFinish: Option[ActorRef] = None
  private var nSkimEvents: Int = 0

  private var nRouteSent: Int = 0
  private var nSuccessRoutes: Int = 0
  private var nFailedRoutes: Int = 0

  private var maybeMonitor: Option[Cancellable] = None
  private var started: Boolean = false
  private var startedAt: Long = 0

  def totalResponses: Int = nSuccessRoutes + nFailedRoutes

  def receive: Receive = {
    case resp: ODRequester.Response =>
      checkIfNeedToStop(sender())
      processResponse(resp)
      checkAndGiveTheResult()

    case Terminated(ref) =>
      workers -= ref
      workersToRemove -= ref
      workerToEc.get(ref).foreach(_.shutdown())
      log.debug(s"Received termination of $ref")

    case msg: Request =>
      msg match {
        case Request.BatchResponse(responses) =>
          checkIfNeedToStop(sender())
          var i = 0
          while (i < responses.length) {
            processResponse(responses(i))
            i += 1
          }
          checkAndGiveTheResult()

        case Monitor =>
          logStat()

        case Request.Start =>
          if (!started) {
            val parallelismSource =
              if (requestedParallelism > 0) "explicitly requested" else "auto-scaled (80% of CPUs)"
            log.info(s"Starting with $initialWorkers workers ($parallelismSource, $availableProcessors CPUs available)")
            (1 to initialWorkers).foreach { _ =>
              val (workerRef, ec) = createWorker()
              context.watch(workerRef)
              addWorkerWithEc(workerRef, ec)
            }

            started = true
            startedAt = System.currentTimeMillis()
            maybeMonitor = Some(createMonitor)
          }
        case Request.Stop =>
          context.children.foreach(_ ! PoisonPill)
          workerToEc.values.foreach(_.shutdown())
          context.stop(self)

        case Request.IncreaseParallelismTo(parallelism) =>
          log.info(s"Need to increase parallelism to $parallelism. Current number of workers: ${workers.size}")
          val nCreateWorkers = parallelism - workers.size
          // Allow explicit requests up to 100% of available processors
          if (nCreateWorkers > 0 && parallelism <= availableProcessors) {
            (1 to nCreateWorkers).foreach { _ =>
              val (worker, ec) = createWorker()
              addWorkerWithEc(worker, ec)
              context.watch(worker)
            }
          } else {
            log.warning(s"Provided parallelism $parallelism is out of the range (0, $availableProcessors]")
          }
        case Request.ReduceParallelismTo(parallelism) =>
          val newWorkers = workers.take(parallelism)
          val workersToStop = workers.drop(parallelism)
          log.info(s"Need to reduce parallelism to $parallelism. Number of workers to stop: ${workersToStop.size}")
          // Workers resolve routes in the Future, so we should keep them in separate list until we get next message from the worker
          workersToRemove ++= workersToStop
          workers = newWorkers
        case Request.WaitToFinish =>
          replyToWhenFinish = Some(sender())
          checkAndGiveTheResult()
        case Request.GiveMoreWork(worker) =>
          checkAndGiveTheResult()
          checkIfNeedToStop(worker)
          if (workers.contains(worker)) {
            if (moreWorkExist) {
              if (stage == 2) {
                val batch = getNextReturnWorkItemBatch(batchSize)
                nRouteSent += batch.length
                worker ! Response.EnhancedWorkBatch(batch)
              } else if (useEnhancedMode) {
                val batch = getNextWorkItemBatch(batchSize)
                nRouteSent += batch.length
                worker ! Response.EnhancedWorkBatch(batch)
              } else {
                val batch = getNextBatch(batchSize)
                nRouteSent += batch.length
                worker ! Response.WorkBatch(batch)
              }
            } else {
              worker ! Response.NoWork
            }
          } else {
            log.info(s"Worker $worker is not in the worker list, not going to give work to it.")
          }
      }
    case x =>
      log.error(s"Don't know what to do with message of type ${x.getClass}: $x")
  }

  private def checkIfNeedToStop(worker: ActorRef): Unit = {
    if (workersToRemove.contains(worker)) {
      log.info(s"Worker $worker in the list for removal. Stopping it")
      worker ! PoisonPill
    }
  }

  private def addWorkerWithEc(worker: ActorRef, ec: ExecutionContextExecutorService): Unit = {
    workers += worker
    workerToEc += worker -> ec
  }

  private def moreWorkExist: Boolean = {
    if (stage == 1) {
      if (useEnhancedMode) {
        currentWorkItemIdx < workItems.length
      } else {
        currentIdx < ODs.length && currentTime < requestTimes.length
      }
    } else {
      currentReturnWorkItemIdx < returnWorkItems.length
    }
  }

  private def getNextODTime: (GeoIndex, GeoIndex, Int) = {
    val requestTime = requestTimes(currentTime)
    val (o, d) = ODs(currentIdx)

    currentTime += 1
    if (currentTime >= requestTimes.length) {
      currentTime = 0
      currentIdx += 1
    }

    (o, d, requestTime)
  }

  /**
    * Get next batch of work items for enhanced mode stage 1.
    */
  private def getNextWorkItemBatch(size: Int): Array[ODWorkItem] = {
    val endIdx = Math.min(currentWorkItemIdx + size, workItems.length)
    val batch = workItems.slice(currentWorkItemIdx, endIdx)
    currentWorkItemIdx = endIdx
    batch
  }

  /**
    * Get next batch of work items for stage 2 (return trips with candidate parking locations).
    */
  private def getNextReturnWorkItemBatch(size: Int): Array[ODWorkItem] = {
    val endIdx = Math.min(currentReturnWorkItemIdx + size, returnWorkItems.length)
    val batch = returnWorkItems.slice(currentReturnWorkItemIdx, endIdx)
    currentReturnWorkItemIdx = endIdx
    batch
  }

  private def getNextBatch(size: Int): Array[(GeoIndex, GeoIndex, Int)] = {
    val result = new scala.collection.mutable.ArrayBuffer[(GeoIndex, GeoIndex, Int)](size)
    var count = 0
    while (count < size && legacyModeWorkExist) {
      val requestTime = requestTimes(currentTime)
      val (o, d) = ODs(currentIdx)
      currentTime += 1
      if (currentTime >= requestTimes.length) {
        currentTime = 0
        currentIdx += 1
      }
      result += ((o, d, requestTime))
      count += 1
    }
    result.toArray
  }

  private def legacyModeWorkExist: Boolean = {
    currentIdx < ODs.length && currentTime < requestTimes.length
  }

  private def totalTimeFromRequestToArrival(trip: EmbodiedBeamTrip, requestTime: Int): Int = {
    trip.legs.lastOption match {
      case Some(lastLeg) => Math.max(0, lastLeg.beamLeg.endTime - requestTime)
      case None          => trip.totalTravelTimeInSecs
    }
  }

  private def extractParkingLocation(trip: EmbodiedBeamTrip): Option[Coord] = {
    val legs = trip.legs
    val carLegIdx = legs.indexWhere(leg =>
      leg.beamLeg.mode == BeamMode.CAR || leg.beamLeg.mode == BeamMode.DRIVE_TRANSIT
    )
    if (carLegIdx >= 0) {
      val hasTransitAfter = legs.drop(carLegIdx + 1).exists(_.beamLeg.mode.isTransit)
      if (hasTransitAfter) {
        val carLeg = legs(carLegIdx)
        val endPoint = carLeg.beamLeg.travelPath.endPoint
        if (endPoint != null && endPoint.loc != null) {
          Some(odRequester.geoUtils.wgs2Utm(endPoint.loc))
        } else {
          None
        }
      } else {
        None
      }
    } else {
      None
    }
  }

  private def addParkingLocation(set: scala.collection.mutable.Set[Coord], newCoord: Coord): Unit = {
    val duplicate = set.exists { existing =>
      val dx = existing.getX - newCoord.getX
      val dy = existing.getY - newCoord.getY
      (dx * dx + dy * dy) < (50.0 * 50.0)
    }
    if (!duplicate) {
      set.add(newCoord)
    }
  }

  private def processResponse(resp: ODRequester.Response): Unit = {
    resp.maybeRoutingResponse match {
      case Failure(ex) =>
        nFailedRoutes += 1
        log.error(ex, s"Can't compute route: ${ex.getMessage}")
      case Success(routingResponse) =>
        nSuccessRoutes += 1
        val validTrips = routingResponse.itineraries.filterNot(isBikeTransit)
        if (validTrips.nonEmpty) {
          // If WALK was not requested as an active mode (e.g. this is a transit query),
          // filter out direct WALK fallback itineraries so they do not pollute walk skims.
          val filteredTrips = if (!odRequester.buildDirectWalkRoute) {
            validTrips.filterNot(_.tripClassifier == BeamMode.WALK)
          } else {
            validTrips
          }

          val tripsToRecord: Iterable[EmbodiedBeamTrip] = abstractSkimmer match {
            case _: ActivitySimSkimmer =>
              // ActivitySim: group itineraries by (ActivitySimPathType, fleet) and pick the fastest trip
              // from request time to arrival time (secondary sort on total travel time)
              filteredTrips
                .groupBy(ActivitySimPathType.determineTripPathTypeAndFleet)
                .filterKeys(_._1 != ActivitySimPathType.OTHER)
                .values
                .map { modeTrips =>
                  modeTrips.minBy(t => (totalTimeFromRequestToArrival(t, resp.requestTime), t.totalTravelTimeInSecs))
                }
            case _ =>
              // Default/ODSkimmer: group itineraries by BeamMode and pick the fastest trip
              filteredTrips
                .groupBy(_.tripClassifier)
                .values
                .map { modeTrips =>
                  modeTrips.minBy(t => (totalTimeFromRequestToArrival(t, resp.requestTime), t.totalTravelTimeInSecs))
                }
          }

          tripsToRecord.foreach { trip =>
            try {
              if (resp.avoidTolls) {
                // Stage 2 toll-avoiding route: record as SOV (unavoidable bridge toll)
                val unavoidableToll = odRequester.calculateToll(trip)
                val event = odRequester.createActivitySimSkimEvent(
                  resp.srcIndex,
                  resp.dstIndex,
                  ActivitySimPathType.SOV,
                  trip,
                  resp.requestTime,
                  tollCostInDollars = unavoidableToll,
                  bridgeTollInCents = unavoidableToll * 100.0,
                  valueTollInCents = 0.0
                )
                abstractSkimmer.handleEvent(event)
                nSkimEvents += 1
              } else if (
                bifurcateTolls && abstractSkimmer.isInstanceOf[ActivitySimSkimmer] && trip.tripClassifier == BeamMode.CAR
              ) {
                val toll = odRequester.calculateToll(trip)
                if (toll == 0.0) {
                  // Toll-free route: serves as both SOV and SOVTOLL
                  val sovEvent = odRequester.createActivitySimSkimEvent(
                    resp.srcIndex,
                    resp.dstIndex,
                    ActivitySimPathType.SOV,
                    trip,
                    resp.requestTime,
                    tollCostInDollars = 0.0,
                    bridgeTollInCents = 0.0,
                    valueTollInCents = 0.0
                  )
                  abstractSkimmer.handleEvent(sovEvent)
                  nSkimEvents += 1

                  val sovTollEvent = odRequester.createActivitySimSkimEvent(
                    resp.srcIndex,
                    resp.dstIndex,
                    ActivitySimPathType.SOVTOLL,
                    trip,
                    resp.requestTime,
                    tollCostInDollars = 0.0,
                    bridgeTollInCents = 0.0,
                    valueTollInCents = 0.0
                  )
                  abstractSkimmer.handleEvent(sovTollEvent)
                  nSkimEvents += 1
                } else {
                  // Tolled route: record as SOVTOLL with optional value toll (VTOLL)
                  // and queue for toll-avoiding pass
                  val sovTollEvent = odRequester.createActivitySimSkimEvent(
                    resp.srcIndex,
                    resp.dstIndex,
                    ActivitySimPathType.SOVTOLL,
                    trip,
                    resp.requestTime,
                    tollCostInDollars = toll,
                    bridgeTollInCents = 0.0,
                    valueTollInCents = toll * 100.0
                  )
                  abstractSkimmer.handleEvent(sovTollEvent)
                  nSkimEvents += 1

                  if (stage == 1) {
                    tolledCarODs.add((resp.srcIndex, resp.dstIndex, resp.requestTime))
                  }
                }
              } else {
                val event = odRequester.createSkimEvent(
                  resp.srcIndex,
                  resp.dstIndex,
                  trip.tripClassifier,
                  trip,
                  resp.requestTime
                )
                abstractSkimmer.handleEvent(event)
                nSkimEvents += 1
              }
            } catch {
              case NonFatal(ex) =>
                log.error(ex, s"Can't create skim event: ${ex.getMessage}")
            }
          }

          // Accumulate parking locations for outbound drive-transit trips during Stage 1
          if (generateReturnTrips && stage == 1) {
            filteredTrips.foreach { trip =>
              extractParkingLocation(trip).foreach { parkingCoord =>
                val set = outboundStationParkingSets.getOrElseUpdate(
                  (resp.srcIndex, resp.dstIndex),
                  scala.collection.mutable.Set.empty
                )
                addParkingLocation(set, parkingCoord)
              }
            }
          }
        }
    }
  }

  private def checkAndGiveTheResult(): Unit = {
    if (stage == 1 && totalResponses == stage1TotalRequests) {
      val hasReturnTrips = generateReturnTrips && outboundStationParkingSets.nonEmpty
      val hasTolledCarTrips = bifurcateTolls && tolledCarODs.nonEmpty
      if (hasReturnTrips || hasTolledCarTrips) {
        transitionToStage2()
      } else {
        finishAndReply()
      }
    } else if (stage == 2 && totalResponses == maxRequestsNumber) {
      finishAndReply()
    }
  }

  private def transitionToStage2(): Unit = {
    stage = 2
    log.info(
      s"Stage 1 complete! Total responses: $totalResponses. " +
      s"Collected parking locations for ${outboundStationParkingSets.size} OD pairs across ${requestTimes.length} time periods. " +
      s"Tolled car ODs for second pass: ${tolledCarODs.size}. " +
      s"Generating Stage 2 work items..."
    )

    val categories: Seq[Option[TransitModeCategory]] = if (transitModeCategories.nonEmpty) {
      transitModeCategories.map(Some(_))
    } else {
      Seq(None)
    }

    val returnItems = if (generateReturnTrips && outboundStationParkingSets.nonEmpty) {
      for {
        ((outboundSrc, outboundDst), parkingSet) <- outboundStationParkingSets.toArray
        time <- requestTimes
        category <- categories
      } yield ODWorkItem(
        srcIndex = outboundDst,
        dstIndex = outboundSrc,
        time = time,
        transitCategory = category,
        tripDirection = TripDirection.Return,
        parkingLocations = parkingSet.toSet
      )
    } else {
      Array.empty[ODWorkItem]
    }

    val tollAvoidItems = if (bifurcateTolls && tolledCarODs.nonEmpty) {
      tolledCarODs.toArray.map { case (src, dst, time) =>
        ODWorkItem(
          srcIndex = src,
          dstIndex = dst,
          time = time,
          avoidTolls = true
        )
      }
    } else {
      Array.empty[ODWorkItem]
    }

    returnWorkItems = returnItems ++ tollAvoidItems
    stage2TotalRequests = returnWorkItems.length
    maxRequestsNumber = stage1TotalRequests + stage2TotalRequests
    currentReturnWorkItemIdx = 0

    log.info(
      s"Stage 2 started: $stage2TotalRequests work items to process (${returnItems.length} return transit, ${tollAvoidItems.length} toll-avoid auto). " +
      s"Total requests across both stages: $maxRequestsNumber"
    )

    // Proactively dispatch initial return batches to all available workers
    workers.foreach { worker =>
      if (moreWorkExist) {
        val batch = getNextReturnWorkItemBatch(batchSize)
        nRouteSent += batch.length
        worker ! Response.EnhancedWorkBatch(batch)
      } else {
        worker ! Response.NoWork
      }
    }
  }

  private def finishAndReply(): Unit = {
    log.info(
      s"Skimming completed! Total responses: $totalResponses, success: $nSuccessRoutes, failed: $nFailedRoutes, events: $nSkimEvents"
    )
    replyToWhenFinish.foreach { actorRef =>
      actorRef ! PopulatedSkimmer(abstractSkimmer)
    }
  }

  private def createWorker(): (ActorRef, ExecutionContextExecutorService) = {
    val ec: ExecutionContextExecutorService = createSingleThreadExecutionContext
    (context.actorOf(WorkerActor.props(self, odRequester, ec)), ec)
  }

  private def createSingleThreadExecutionContext: ExecutionContextExecutorService = {
    val execSvc: ExecutorService = Executors.newSingleThreadExecutor(
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("OD-R5-requester-%d").build()
    )
    ExecutionContext.fromExecutorService(execSvc)
  }

  private def createMonitor: Cancellable = {
    context.system.scheduler.scheduleWithFixedDelay(30.seconds, 5.minutes, self, Request.Monitor)(context.dispatcher)
  }

  private def isBikeTransit(trip: EmbodiedBeamTrip): Boolean = {
    trip.tripClassifier == BeamMode.WALK_TRANSIT && trip.beamLegs.exists(leg => leg.mode == BeamMode.BIKE)
  }

  private def logStat(): Unit = {
    lazy val dtInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startedAt)
    lazy val avgRoutePerSecond = (nSuccessRoutes + nFailedRoutes).toDouble / dtInSeconds
    log.info(
      s"""Stage $stage | nRouteSent: $nRouteSent out of $maxRequestsNumber (${(nRouteSent.toFloat / maxRequestsNumber * 100).toInt}%), nSuccessRoutes: $nSuccessRoutes, nFailedRoutes: $nFailedRoutes, nSkimEvents: $nSkimEvents
         |AVG route per second: $avgRoutePerSecond, elapsed time: $dtInSeconds seconds
         |Current number of workers: ${workers.size}""".stripMargin
    )
  }
}

object MasterActor {
  sealed trait Request

  object Request {
    private[urbansim] case object Monitor extends Request
    case object Start extends Request
    case object Stop extends Request
    case class IncreaseParallelismTo(parallelism: Int) extends Request
    case class ReduceParallelismTo(parallelism: Int) extends Request
    case object WaitToFinish extends Request
    case class GiveMoreWork(sender: ActorRef) extends Request
    case class BatchResponse(responses: Array[ODRequester.Response]) extends Request
  }

  sealed trait Response

  object Response {
    case class Work(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int) extends Response
    case class WorkBatch(items: Array[(GeoIndex, GeoIndex, Int)]) extends Response
    case class EnhancedWorkBatch(items: Array[ODWorkItem]) extends Response
    case object NoWork extends Response

    case class PopulatedSkimmer(abstractSkimmer: AbstractSkimmer) extends Response
  }

  def props(
    abstractSkimmer: AbstractSkimmer,
    odR5Requester: ODRequester,
    requestTimes: Seq[Int],
    ODs: Array[(GeoIndex, GeoIndex)],
    transitModeCategories: Seq[TransitModeCategory] = Seq.empty,
    generateReturnTrips: Boolean = false,
    bifurcateTolls: Boolean = false,
    parallelism: Int = 0
  ): Props = {
    Props(
      new MasterActor(
        abstractSkimmer,
        odR5Requester,
        requestTimes,
        ODs,
        transitModeCategories,
        generateReturnTrips,
        bifurcateTolls,
        parallelism
      )
    )
  }
}
