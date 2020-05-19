package beam.physsim.bprsim

import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  *
  * @author Dmitry Openkov
  */
class Coordinator(
  clusters: Vector[Set[Id[Link]]],
  scenario: Scenario,
  config: BPRSimConfig,
  eventManager: EventsManager
) extends StrictLogging {
  private val executorService =
    Executors.newFixedThreadPool(clusters.size, new ThreadFactoryBuilder().setNameFormat("par-bpr-thread-%d").build())
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)

  val workers: Vector[BPRSimWorker] = clusters.map(links => new BPRSimWorker(scenario, config, links))
  val workerMap: Map[Id[Link], BPRSimWorker] = workers.flatMap(worker => worker.myLinks.map(_ -> worker)).toMap

  def start(): Unit = {
    workers.foreach(_.init())
    val tillTime = workers.map(_.minTime).min + config.syncInterval
    executePeriod(tillTime)
    executorService.shutdown()
  }

  @tailrec
  private def executePeriod(tillTime: Double): Unit = {
    val events = executeSubPeriod(tillTime, Vector.empty[Event])
    flushEvents(events)
    val minTime = workers.map(_.minTime).min
    if (minTime != Double.MaxValue) {
      executePeriod(minTime + config.syncInterval)
    }
  }

  @tailrec
  private def executeSubPeriod(tillTime: Double, eventAcc: Vector[Event]): Vector[Event] = {
    val future = Future.sequence(workers.map(w => Future(w.processQueuedEvents(workerMap, tillTime))))
    val events: Vector[(Seq[Event], collection.Map[BPRSimWorker, Seq[SimEvent]])] = Await.result(future, Duration.Inf)
    val workerEvents = events.map { case (_, workerToEvents) => workerToEvents }
    val workerToEventMap = group(workerEvents)
    val acceptedEvents = workerToEventMap.map {
      case (worker, events) => worker.acceptEvents(events)
    }
    logger.debug(s"Accepted events: ${acceptedEvents.mkString(",")}")
    val allEvents = eventAcc ++ events.flatMap { case (evs, _) => evs }
    val minTime = workers.map(_.minTime).min
    if (minTime > tillTime) {
      allEvents
    } else {
      executeSubPeriod(tillTime, allEvents)
    }
  }

  def group(
    workerEvents: Vector[collection.Map[BPRSimWorker, Seq[SimEvent]]]
  ): mutable.Map[BPRSimWorker, ArrayBuffer[SimEvent]] = {
    val result = mutable.Map.empty[BPRSimWorker, ArrayBuffer[SimEvent]]
    workerEvents.foreach { map =>
      map.foreach {
        case (w, evs) =>
          val prev = result.getOrElseUpdate(w, ArrayBuffer.empty[SimEvent])
          prev ++= evs
      }
    }
    result
  }

  private def flushEvents(events: Vector[Event]): Unit = {
    import BPRSimulation.eventTimeOrdering
    val sorted = util.Sorting.stableSort(events)
    sorted.foreach(eventManager.processEvent)
  }

}
