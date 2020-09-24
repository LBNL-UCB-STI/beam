package beam.physsim.bprsim

import java.util.concurrent.{Executors, TimeUnit}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager

import scala.annotation.tailrec
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
    Executors.newFixedThreadPool(
      clusters.size,
      new ThreadFactoryBuilder()
        .setNameFormat("par-bpr-thread-%d")
        .setDaemon(true)
        .build()
    )
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)
  private val eventExecutor =
    Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("main-bpr-thread").build())
  val eventEC: ExecutionContext = ExecutionContext.fromExecutor(eventExecutor)

  val workers: Vector[BPRSimWorker] = clusters.map(links => new BPRSimWorker(scenario, config, links))
  val workerMap: Map[Id[Link], BPRSimWorker] = workers.flatMap(worker => worker.myLinks.map(_ -> worker)).toMap

  def start(): Unit = {
    parallelExecution(workers.map(w => () => w.init()))
    val tillTime = workers.map(_.minTime).min + config.syncInterval
    executePeriod(tillTime)
    executorService.shutdown()
    seqFuture.onComplete(_ => eventExecutor.shutdown())(eventEC)
    eventExecutor.awaitTermination(Long.MaxValue, TimeUnit.MILLISECONDS)
  }

  @tailrec
  private def executePeriod(tillTime: Double): Unit = {
    val events = executeSubPeriod(tillTime, Vector.empty[Event])
    asyncFlushEvents(events)
    val minTime = workers.map(_.minTime).min
    if (!minTime.equals(Double.MaxValue)) {
      executePeriod(minTime + config.syncInterval)
    }
  }

  @tailrec
  private def executeSubPeriod(tillTime: Double, eventAcc: Vector[Event]): Vector[Event] = {
    val events: Seq[(Seq[Event], collection.Map[BPRSimWorker, Seq[SimEvent]])] =
      parallelExecution(workers.map(w => () => w.processQueuedEvents(workerMap, tillTime)))
    val (producedEvents, workerEvents) = events.unzip
    val acceptedEvents: Seq[Int] = parallelExecution(workers.map(w => () => w.acceptEvents(workerEvents)))
    logger.debug(s"Accepted events: ${acceptedEvents.mkString(",")}")
    val minTime = workers.map(_.minTime).min
    val allEvents = eventAcc ++ producedEvents.flatten
    if (minTime > tillTime) {
      allEvents
    } else {
      executeSubPeriod(tillTime, allEvents)
    }
  }

  var seqFuture: Future[Unit] = Future.successful(())
  private def asyncFlushEvents(events: Vector[Event]): Unit = {
    seqFuture = seqFuture.flatMap(
      _ =>
        Future {
          import BPRSimulation.eventTimeOrdering
          val sorted = util.Sorting.stableSort(events)
          sorted.foreach(eventManager.processEvent)
        }(eventEC)
    )(eventEC)
  }

  private def parallelExecution[A](functions: Seq[() => A]): Seq[A] = {
    val future = Future.sequence(functions.map(f => Future { f() }))
    Await.result(future, Duration.Inf)
  }

}
