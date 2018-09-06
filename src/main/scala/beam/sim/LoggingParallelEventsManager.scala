package beam.sim
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.events.ParallelEventsManagerImpl
import org.matsim.core.events.handler.EventHandler

class LoggingParallelEventsManager @Inject()(config: Config) extends EventsManager with LazyLogging {
  private val eventManager = new ParallelEventsManagerImpl(config.parallelEventHandling().getNumberOfThreads())
  private val numOfEvents: AtomicInteger = new AtomicInteger(0)
  val threshold: Int = 1000000
  logger.info(s"Created ParallelEventsManagerImpl with hashcode: ${eventManager.hashCode()}")

  override def processEvent(event: Event): Unit = {
    tryLog("processEvent", eventManager.processEvent(event))
    val processed = numOfEvents.incrementAndGet()
    if (processed % threshold == 0)
      logger.info(s"Processed next $threshold events. Total: ${processed}")
  }

  override def addHandler(handler: EventHandler): Unit = {
    tryLog("addHandler", eventManager.addHandler(handler))
  }

  override def removeHandler(handler: EventHandler): Unit = {
    tryLog("removeHandler", eventManager.removeHandler(handler))
  }

  override def resetHandlers(iteration: Int): Unit = {
    tryLog("resetHandlers", eventManager.resetHandlers(iteration))
  }

  override def initProcessing(): Unit = {
    numOfEvents.set(0)
    tryLog("initProcessing", eventManager.initProcessing())
  }

  override def afterSimStep(time: Double): Unit = {
    tryLog("afterSimStep", eventManager.afterSimStep(time))
  }
  override def finishProcessing(): Unit = {
    tryLog("finishProcessing", eventManager.finishProcessing())
    logger.info(s"Overall processed events: ${numOfEvents.get()}")
  }

  private def tryLog(what: String, body: => Unit): Unit = {
    try {
      body
    } catch {
      case t: Throwable =>
        val st = Thread.currentThread.getStackTrace.mkString(System.lineSeparator())
        logger.error(s"Method '$what' failed with: ${t.getMessage}. Stacktrace: $st", t)
    }
  }
}
