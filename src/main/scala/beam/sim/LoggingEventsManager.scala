package beam.sim
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.EventsAccumulator
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.inject.name.Named
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.EventHandler
import org.matsim.core.scoring.{EventsToActivities, EventsToLegs}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class LoggingEventsManager @Inject()(
  @Named("ParallelEM") eventManager: EventsManager
) extends EventsManager
    with LazyLogging {
  private val sequentialHandlers = List(classOf[EventsToActivities], classOf[EventsToLegs])
  private val sequentialEventManager = new EventsManagerImpl()
  private val defaultEventManager = eventManager

  logger.info(
    s"Created sequential ${sequentialEventManager.getClass} with hashcode: ${sequentialEventManager.hashCode()}"
  )
  logger.info(s"Created default ${defaultEventManager.getClass} with hashcode: ${defaultEventManager.hashCode()}")

  private val numOfEvents: AtomicInteger = new AtomicInteger(0)

  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    1,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("logging-events-manager-%d").build()
  )
  private val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)
  private val blockingQueue: BlockingQueue[Event] = new LinkedBlockingQueue[Event]
  private val isFinished: AtomicBoolean = new AtomicBoolean(false)
  private var dedicatedHandler: Option[Future[Unit]] = None
  private val stacktraceToException: collection.mutable.HashMap[StackTraceElement, Exception] =
    collection.mutable.HashMap()

  private var eventsAccumulator: Option[ActorRef] = None

  def setEventsAccumulator(accumulator: Option[ActorRef]): Unit = {
    eventsAccumulator = accumulator
  }

  override def processEvent(event: Event): Unit = {
    eventsAccumulator match {
      case Some(accumulator) =>
        event match {
          case e @ (_: ChargingPlugInEvent | _: ChargingPlugOutEvent | _: RefuelSessionEvent) =>
            accumulator ! EventsAccumulator.ProcessChargingEvents(e)
          case _ =>
        }
      case None =>
    }
    blockingQueue.add(event)
    numOfEvents.incrementAndGet()
  }

  override def addHandler(handler: EventHandler): Unit = {
    if (sequentialHandlers.contains(handler.getClass)) {
      tryLog("addHandler", sequentialEventManager.addHandler(handler))
    } else {
      tryLog("addHandler", defaultEventManager.addHandler(handler))
    }
  }

  override def removeHandler(handler: EventHandler): Unit = {
    tryLog("removeHandler", sequentialEventManager.removeHandler(handler))
    tryLog("removeHandler", defaultEventManager.removeHandler(handler))
  }

  override def resetHandlers(iteration: Int): Unit = {
    tryLog("resetHandlers", sequentialEventManager.resetHandlers(iteration))
    tryLog("resetHandlers", defaultEventManager.resetHandlers(iteration))
  }

  override def initProcessing(): Unit = {
    numOfEvents.set(0)
    stacktraceToException.clear()
    tryLog("initProcessing", sequentialEventManager.initProcessing())
    tryLog("initProcessing", defaultEventManager.initProcessing())
    isFinished.set(false)
    dedicatedHandler = Some(createDedicatedHandler)
  }

  override def afterSimStep(time: Double): Unit = {
    tryLog("afterSimStep", sequentialEventManager.afterSimStep(time))
    tryLog("afterSimStep", defaultEventManager.afterSimStep(time))
  }
  override def finishProcessing(): Unit = {
    val s = System.currentTimeMillis()
    isFinished.set(true)
    logger.debug("Set `isFinished` to true")
    dedicatedHandler.foreach { f =>
      logger.info(
        s"Starting to wait dedicatedHandler future to finish... Number of events in the queue to process: ${blockingQueue.size()}"
      )
      Await.result(f, 1000.seconds)
      logger.info("dedicatedHandler future finished.")
    }
    tryLog("finishProcessing", sequentialEventManager.finishProcessing())
    tryLog("finishProcessing", defaultEventManager.finishProcessing())
    val e = System.currentTimeMillis()
    logger.info(s"finishProcessing executed in ${e - s} ms")
    logger.info(s"Overall processed events: ${numOfEvents.get()}")

    logErrorsDuringEventProcessing()
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
      sequentialEventManager.processEvent(event)
      defaultEventManager.processEvent(event)
    } catch {
      case ex: Exception =>
        if (shouldLog(ex)) {
          logger.error(s"Failed to process event '$event'. Error: ${ex.getMessage}", ex)
          getSource(ex).foreach { ste =>
            stacktraceToException.put(ste, ex)
          }
        }
    }
  }

  private def logErrorsDuringEventProcessing(): Unit = {
    if (stacktraceToException.nonEmpty) {
      logger.error("There were errors during events processing: ")
      stacktraceToException.foreach {
        case (st, ex) =>
          logger.error(st.toString, ex)
      }
    }
  }

  private def getSource(ex: Exception): Option[StackTraceElement] = {
    // http://jawspeak.com/2010/05/26/hotspot-caused-exceptions-to-lose-their-stack-traces-in-production-and-the-fix/
    // JVM can optimize away stack traces in certain exceptions if they happen enough, that's why `ex.getStackTrace.headOption`
    ex.getStackTrace.headOption
  }

  private def shouldLog(ex: Exception): Boolean = {
    getSource(ex).exists { ste =>
      val shouldLog = !stacktraceToException.contains(ste)
      shouldLog
    }
  }
}
