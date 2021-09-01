package beam.physsim.bprsim

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.EventHandler

import java.util.concurrent.{ExecutorService, Executors, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * @author Dmitry Openkov
  */
class BatchEventManager extends EventsManager {

  override def processEvent(event: Event): Unit = throw new NotImplementedError(
    "Use processEvents method for batch processing"
  )

  private val managers = ArrayBuffer.empty[EventsManagerImpl]
  private val queues = ArrayBuffer.empty[LinkedBlockingQueue[Array[Event]]]
  private var executorService: ExecutorService = _
  private var future: Future[ArrayBuffer[Unit]] = _

  def processEvents(events: Array[Event]): Unit = {
    queues.foreach(_.put(events))
  }

  override def addHandler(handler: EventHandler): Unit = {
    val manager = new EventsManagerImpl
    manager.addHandler(handler)
    managers.append(manager)
    queues.append(new LinkedBlockingQueue[Array[Event]]())
  }

  override def removeHandler(handler: EventHandler): Unit = throw new NotImplementedError()

  override def resetHandlers(iteration: Int): Unit = throw new NotImplementedError()

  override def initProcessing(): Unit = {
    if (executorService == null) {
      executorService = Executors.newFixedThreadPool(
        managers.size,
        new ThreadFactoryBuilder()
          .setNameFormat("batch-event-manager-thread-%d")
          .setDaemon(true)
          .build()
      )
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executorService)
      future = Future.sequence(
        managers.zip(queues).map { case (manager, queue) =>
          Future {
            manager.initProcessing()
            processEventQueue(manager, queue)
          }
        }
      )
    }
  }

  @tailrec
  private def processEventQueue(manager: EventsManagerImpl, queue: LinkedBlockingQueue[Array[Event]]): Unit = {
    val events = queue.take()
    if (events.nonEmpty) {
      events.foreach(event => manager.processEvent(event))
      processEventQueue(manager, queue)
    }
  }

  override def afterSimStep(time: Double): Unit = managers.foreach(_.afterSimStep(time))

  override def finishProcessing(): Unit = {
    queues.foreach(_.put(Array.empty))
    Await.result(future, Duration.Inf)
    managers.foreach(_.finishProcessing())
    executorService.shutdown()
  }
}
