package beam.sim
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.events.ParallelEventsManagerImpl
import org.matsim.core.events.handler.EventHandler

class LoggingParallelEventsManager @Inject()(config: Config) extends EventsManager with LazyLogging {
  private val eventManager = new ParallelEventsManagerImpl(config.parallelEventHandling().getNumberOfThreads())

  logger.info(s"Created ParallelEventsManagerImpl with hashcode: ${eventManager.hashCode()}")

  override def processEvent(event: Event): Unit = {
    tryLog("processEvent", eventManager.processEvent(event))
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
    tryLog("initProcessing", eventManager.initProcessing())
  }

  override def afterSimStep(time: Double): Unit = {
    tryLog("afterSimStep", eventManager.afterSimStep(time))
  }
  override def finishProcessing(): Unit = {
    tryLog("finishProcessing", eventManager.finishProcessing())
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
