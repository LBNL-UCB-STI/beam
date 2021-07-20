package beam.router.skim.urbansim

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PoisonPill, Props, Terminated}
import beam.agentsim.infrastructure.geozone.GeoIndex
import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.core.AbstractSkimmer
import beam.router.skim.urbansim.MasterActor.Request.Monitor
import beam.router.skim.urbansim.MasterActor.Response.PopulatedSkimmer
import beam.router.skim.urbansim.MasterActor.{Request, Response}
import com.google.common.util.concurrent.ThreadFactoryBuilder

import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class MasterActor(
  val abstractSkimmer: AbstractSkimmer,
  val odRequester: ODRequester,
  val requestTimes: Seq[Int],
  val ODs: Array[(GeoIndex, GeoIndex)]
) extends Actor
    with ActorLogging {

  override def postStop(): Unit = {
    logStat()
    log.info(s"Stopping $self")
    maybeMonitor.foreach(_.cancel())
  }

  private val maxWorkers: Int = Runtime.getRuntime.availableProcessors()

  private val maxRequestsNumber: Int = ODs.length * requestTimes.length

  private var currentIdx: Int = 0
  private var currentTime: Int = 0

  log.info(
    s"Total number of OD pairs: ${ODs.length}, number of request time entries: ${requestTimes.length}, maxWorkers: $maxWorkers"
  )

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
      resp.maybeRoutingResponse match {
        case Failure(ex) =>
          nFailedRoutes += 1
          log.error(ex, s"Can't compute route: ${ex.getMessage}")
        case Success(routingResponse) =>
          nSuccessRoutes += 1
          routingResponse.itineraries.foreach { trip =>
            if (!isBikeTransit(trip)) {
              try {
                val event = odRequester.createSkimEvent(
                  resp.srcIndex,
                  resp.dstIndex,
                  trip.tripClassifier,
                  trip,
                  resp.requestTime
                )
                abstractSkimmer.handleEvent(event)
                nSkimEvents += 1
              } catch {
                case NonFatal(ex) =>
                  log.error(ex, s"Can't create skim event: ${ex.getMessage}")
              }
            }
          }
      }

    case Terminated(ref) =>
      workers -= ref
      workersToRemove -= ref
      workerToEc.get(ref).foreach(_.shutdown())
      log.debug(s"Received termination of $ref")

    case msg: Request =>
      msg match {
        case Monitor =>
          logStat()

        case Request.Start =>
          if (!started) {
            val (workerRef, ec) = createWorker()
            context.watch(workerRef)
            addWorkerWithEc(workerRef, ec)

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
          if (nCreateWorkers > 0 && parallelism <= maxWorkers) {
            (1 to nCreateWorkers).foreach { _ =>
              val (worker, ec) = createWorker()
              addWorkerWithEc(worker, ec)
              context.watch(worker)
            }
          } else {
            log.warning(s"Provided parallelism $parallelism is out of the range (0, $maxWorkers]")
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
              val (srcIndex, dstIndex, requestTime) = getNextODTime
              nRouteSent += 1
              worker ! Response.Work(srcIndex, dstIndex, requestTime)
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
    currentIdx < ODs.length && currentTime < requestTimes.length
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

  private def checkAndGiveTheResult(): Unit = {
    if (totalResponses == ODs.length * requestTimes.length) {
      replyToWhenFinish.foreach { actorRef =>
        actorRef ! PopulatedSkimmer(abstractSkimmer)
      }
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
      s"""nRouteSent: $nRouteSent out of $maxRequestsNumber (${(nRouteSent.toFloat / maxRequestsNumber * 100).toInt}%), nSuccessRoutes: $nSuccessRoutes, nFailedRoutes: $nFailedRoutes, nSkimEvents: $nSkimEvents
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
  }

  sealed trait Response

  object Response {
    case class Work(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int) extends Response
    case object NoWork extends Response

    case class PopulatedSkimmer(abstractSkimmer: AbstractSkimmer) extends Response
  }

  def props(
    abstractSkimmer: AbstractSkimmer,
    odR5Requester: ODRequester,
    requestTimes: Seq[Int],
    ODs: Array[(GeoIndex, GeoIndex)]
  ): Props = {
    Props(new MasterActor(abstractSkimmer, odR5Requester: ODRequester, requestTimes, ODs))
  }
}
