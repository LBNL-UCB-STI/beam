package beam.router.skim.urbansim

import java.util.concurrent.{ExecutorService, Executors}

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import beam.agentsim.infrastructure.geozone.{GeoZoneSummaryItem, H3Index}
import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.ODSkimmer
import beam.router.skim.urbansim.MasterActor.Response.PopulatedSkimmer
import beam.router.skim.urbansim.MasterActor.{Request, Response}
import com.google.common.util.concurrent.ThreadFactoryBuilder

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class MasterActor(
  val h3Clustering: H3Clustering,
  val odSkimmer: ODSkimmer,
  val odR5Requester: ODR5Requester
) extends Actor
    with ActorLogging {

  private val maxWorkers: Int = Runtime.getRuntime.availableProcessors()
  private val indexes: Seq[GeoZoneSummaryItem] = h3Clustering.h3Indexes.take(100)

  private val allODPairs: Iterator[(H3Index, H3Index)] = indexes.flatMap { srcGeo =>
    indexes.map { dstGeo =>
      (srcGeo.index, dstGeo.index)
    }
  }.toIterator

  private var workers: Set[ActorRef] = Set.empty
  private var replyToWhenFinish: Option[ActorRef] = None
  private var nSkimEvents: Int = 0

  private def isBikeTransit(trip: EmbodiedBeamTrip): Boolean = {
    trip.tripClassifier == BeamMode.WALK_TRANSIT && trip.beamLegs.exists(leg => leg.mode == BeamMode.BIKE)
  }

  def receive: Receive = {
    case resp: ODR5Requester.Response =>
      resp.maybeRoutingResponse match {
        case Failure(ex) =>
          log.error(ex, s"Can't compute route: ${ex.getMessage}")
        case Success(routingResponse) =>
          routingResponse.itineraries.foreach { trip =>
            if (resp.considerModes.contains(trip.tripClassifier) && !isBikeTransit(trip)) {
              try {
                val event = odR5Requester.createSkimEvent(resp.srcIndex, resp.dstIndex, trip.tripClassifier, trip)
                odSkimmer.handleEvent(event)
                nSkimEvents += 1
              } catch {
                case NonFatal(ex) =>
                  log.error(ex, s"Can't create skim event: ${ex.getMessage}")
              }
            }
          }
      }

    case Terminated(ref) =>
      log.info(s"Received termination of $ref")

    case msg: Request =>
      msg match {
        case Request.Start =>
          val workerRef = createWorker()
          context.watch(workerRef)
          workers = workers + workerRef

        case Request.IncreaseParallelismTo(parallelism) =>
          log.info(s"Need to increase parallelism to $parallelism. Current number of workers: ${workers.size}")
          val nCreateWorkers = parallelism - workers.size
          if (nCreateWorkers > 0 && parallelism <= maxWorkers) {
            val newWorkers = (1 to nCreateWorkers).map { _ =>
              createWorker()
            }
            newWorkers.foreach(context.watch)
            workers = workers ++ newWorkers
          } else {
            log.warning(s"Provided parallelism $parallelism is out of the range (0, $maxWorkers]")
          }
        case Request.ReduceParallelismTo(parallelism) =>
          val newWorkers = workers.take(parallelism)
          val workersToStop = workers.drop(parallelism)
          log.info(s"Need to reduce parallelism to $parallelism. Number of workers to stop: ${workersToStop.size}")
          workersToStop.foreach { worker =>
            worker ! PoisonPill
          }
          workers = newWorkers
        case Request.WaitToFinish(sender) =>
          replyToWhenFinish = Some(sender)
        case Request.GiveMoreWork(worker) =>
          if (allODPairs.isEmpty) {
            replyToWhenFinish.foreach(_ ! PopulatedSkimmer(odSkimmer))
          } else if (workers.contains(worker)) {
            if (allODPairs.nonEmpty) {
              val (srcIndex, dstIndex) = allODPairs.next()
              worker ! Response.Work(srcIndex, dstIndex)
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

  def createWorker(): ActorRef = {
    context.actorOf(WorkerActor.props(self, odR5Requester, createSingleThreadExecutionContext))
  }

  def createSingleThreadExecutionContext: ExecutionContext = {
    val execSvc: ExecutorService = Executors.newSingleThreadExecutor(
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("OD-R5-requester-%d").build()
    )
    ExecutionContext.fromExecutorService(execSvc)
  }
}

object MasterActor {
  sealed trait Request

  object Request {
    case object Start extends Request
    case class IncreaseParallelismTo(parallelism: Int) extends Request
    case class ReduceParallelismTo(parallelism: Int) extends Request
    case class WaitToFinish(sender: ActorRef) extends Request

    case class GiveMoreWork(sender: ActorRef) extends Request
  }

  sealed trait Response

  object Response {
    case class Work(srcIndex: H3Index, dstIndex: H3Index) extends Response
    case object NoWork extends Response

    case class PopulatedSkimmer(odSkimmer: ODSkimmer) extends Response
  }

  def props(
    h3Clustering: H3Clustering,
    odSkimmer: ODSkimmer,
    odR5Requester: ODR5Requester
  ): Props = {
    Props(new MasterActor(h3Clustering, odSkimmer, odR5Requester: ODR5Requester))
  }
}
