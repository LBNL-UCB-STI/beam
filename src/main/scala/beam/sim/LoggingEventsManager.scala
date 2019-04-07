package beam.sim
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.EventHandler

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class LoggingEventsManager @Inject()(config: Config) extends EventsManager with LazyLogging {
  private val eventManager = new EventsManagerImpl()
  logger.info(s"Created ${eventManager.getClass} with hashcode: ${eventManager.hashCode()}")

  private val numOfEvents: AtomicInteger = new AtomicInteger(0)

  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    1,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("logging-events-manager-%d").build()
  )
  private val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)
  private val blockingQueue: BlockingQueue[Event] = new LinkedBlockingQueue[Event]
  private val isFinished: AtomicBoolean = new AtomicBoolean(false)
  private var dedicatedHandler: Option[Future[Unit]] = None

  override def processEvent(event: Event): Unit = {
    blockingQueue.add(event)
    numOfEvents.incrementAndGet()
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
    isFinished.set(false)
    dedicatedHandler = Some(createDedicatedHandler)
  }

  override def afterSimStep(time: Double): Unit = {
    tryLog("afterSimStep", eventManager.afterSimStep(time))
  }
  override def finishProcessing(): Unit = {
    val s = System.currentTimeMillis()
    isFinished.set(true)
    logger.info("Set `isFinished` to true")
    dedicatedHandler.foreach { f =>
      logger.info("Starting to wait dedicatedHandler future to finish...")
      Await.result(f, 1000.seconds)
      logger.info("dedicatedHandler future finished.")
    }
    tryLog("finishProcessing", eventManager.finishProcessing())
    val e = System.currentTimeMillis()
    logger.info(s"finishProcessing executed in ${e - s} ms")
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

  private def createDedicatedHandler: Future[Unit] = {
    logger.info("Running dedicated blocking handler...")
    Future {
      // Let's go for now with blocking approach because it's safe CPU and pretty good for
      // the scenario when it's many writers but only one reader
      handleBlocking()
    }(executionContext)
  }

  private def handleBlocking(): Unit = {
    while (!isFinished.get()) {
      val event = blockingQueue.poll(1, TimeUnit.SECONDS)
      if (null != event)
        tryToProcessEvent(event)
    }
    // We have to consumer the whole queue
    var isDone = false
    val start = System.currentTimeMillis()
    while (!isDone) {
      val event = blockingQueue.poll()
      if (null == event)
        isDone = true
      else
        tryToProcessEvent(event)
    }
    val end = System.currentTimeMillis()
    logger.info("Stopped dedicated handler(handleBlocking). Took {} ms to process after stop", end - start)
  }
  private def tryToProcessEvent(event: Event): Unit = {
    try {
      eventManager.processEvent(event)
    } catch {
      case t: Throwable =>
        logger.error(s"Failed to process event '$event'. Error: ${t.getMessage}", t)
    }
  }
}
