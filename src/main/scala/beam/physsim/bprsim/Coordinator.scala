package beam.physsim.bprsim

import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.JavaConverters._

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
  val buffer = new ConcurrentLinkedQueue[Event]
  private val executorService =
    Executors.newFixedThreadPool(clusters.size, new ThreadFactoryBuilder().setNameFormat("par-bpr-thread-%d").build())
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)

  val workers: Vector[BPRSimWorker] = clusters.map(links => new BPRSimWorker(scenario, config, links, this))
  val workerMap: Map[Id[Link], BPRSimWorker] = workers.flatMap(worker => worker.myLinks.map(_ -> worker)).toMap

  def start(): Unit = {
    workers.foreach(_.init())
    val tillTime = workers.map(_.minTime).min + 60
    executePeriod(tillTime)
    executorService.shutdown()
  }

  @tailrec
  private def executePeriod(tillTime: Double): Unit = {
    val future = Future.sequence(workers.map(w => Future(w.processQueuedEvents(workerMap, tillTime))))
    val counters = Await.result(future, Duration.Inf)
    flush()
    val minTime = workers.map(_.minTime).min
    if (minTime != Double.MaxValue) {
      executePeriod(minTime + 60)
    }
  }

  def processEvent(event: Event) = buffer.add(event)

  private def flush(): Unit = {
    implicit val ordering: Ordering[Event] = Ordering.by((_: Event).getTime)
    val sorted = util.Sorting.stableSort(buffer.asScala.toVector)
    sorted.foreach(eventManager.processEvent)
    buffer.clear()
  }

}
