package beam.physsim.bprsim

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.annotation.tailrec
import scala.collection.mutable
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
  val buffer = mutable.ArrayBuffer.empty[Event]
  val finishedWorkers = new AtomicInteger(0)
  val numWorkers = clusters.size

  private val executorService =
    Executors.newFixedThreadPool(numWorkers, new ThreadFactoryBuilder().setNameFormat("par-bpr-thread-%d").build())
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)

  val workers: Vector[BPRSimWorker] = clusters.map(links => new BPRSimWorker(scenario, config, links, this))
  val workerMap: Map[Id[Link], BPRSimWorker] = workers.flatMap(worker => worker.myLinks.map(_ -> worker)).toMap

  def start(): Unit = {
    val tillTime = workers.map(_.startingTime).min + 60
    workers.foreach(_.init(tillTime))
    executePeriod(tillTime)
    executorService.shutdown()
  }

  @tailrec
  private def executePeriod(tillTime: Double): Unit = {
    val future = Future.sequence(workers.map(w => Future(w.processQueuedEvents(workerMap, tillTime))))
    logger.debug(s"waiting for finishing processing period till $tillTime")
    val counters = Await.result(future, Duration.Inf)
    logger.debug(s"sim events processed for period $tillTime = ${counters.sum}")
    flush()
    val minTime = workers.map(_.minTime).min
    if (minTime != Double.MaxValue) {
      val nextTime = minTime + 60
      val movedFuture = Future.sequence(workers.map(w => Future(w.moveToQueue(nextTime))))
      val totallyMoved = Await.result(movedFuture, Duration.Inf)
      logger.debug(s"Moved for period = $totallyMoved")
      executePeriod(nextTime)
    }
  }

  def processEvent(event: Event) = buffer += event

  private def flush(): Unit = {
    implicit val ordering: Ordering[Event] = Ordering.by((_: Event).getTime)
    val sorted = util.Sorting.stableSort(buffer)
    sorted.foreach(eventManager.processEvent)
    buffer.clear()
  }

}
