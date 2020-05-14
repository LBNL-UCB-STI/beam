package beam.physsim.bprsim

import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.annotation.tailrec
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
) {
  private val executorService =
    Executors.newFixedThreadPool(clusters.size, new ThreadFactoryBuilder().setNameFormat("par-bpr-thread-%d").build())
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)

  val workers: Vector[BPRSimWorker] = clusters.map(links => new BPRSimWorker(scenario, config, links,
    ArrayBuffer.empty[Event]))
  val workerMap: Map[Id[Link], BPRSimWorker] = workers.flatMap(worker => worker.myLinks.map(_ -> worker)).toMap

  def start(): Unit = {
    workers.foreach(_.init())
    val tillTime = workers.map(_.minTime).min + config.syncInterval
    executePeriod(tillTime)
    executorService.shutdown()
  }

  @tailrec
  private def executePeriod(tillTime: Double): Unit = {
    val future = Future.sequence(workers.map(w => Future(w.processQueuedEvents(workerMap, tillTime))))
    Await.result(future, Duration.Inf)
    flushEvents()
    val minTime = workers.map(_.minTime).min
    if (minTime != Double.MaxValue) {
      executePeriod(minTime + config.syncInterval)
    }
  }

  private def flushEvents(): Unit = {
    implicit val ordering: Ordering[Event] = Ordering.by((_: Event).getTime)
    val sorted = workers.map(_.eventBuffer).reduce(_ ++ _).sorted
    sorted.foreach(eventManager.processEvent)
    workers.foreach(_.eventBuffer.clear())
  }

}
