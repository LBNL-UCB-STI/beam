package beam.physsim.jdeqsim

import java.util.concurrent.{ExecutorService, Executors, LinkedBlockingQueue, TimeUnit}

import beam.agentsim.events.handling._
import beam.analysis.via.EventWriterXML_viaCompatible
import beam.physsim.jdeqsim.PhysSimEventWriter.CommonEventWriter
import beam.sim.BeamServices
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.ShutdownEvent
import org.matsim.core.controler.listener.ShutdownListener
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.events.handler.BasicEventHandler

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class PhysSimEventWriter(writers: Array[CommonEventWriter], itNum: Int)
    extends BasicEventHandler
    with ShutdownListener
    with EventWriter
    with LazyLogging {

  private val writerExecutor: ExecutorService =
    Executors.newFixedThreadPool(
      1,
      new ThreadFactoryBuilder()
        .setNameFormat(s"physsim-event-writer-$itNum")
        .setDaemon(false)
        .build()
    )
  private implicit val writerEC: ExecutionContext = ExecutionContext.fromExecutor(writerExecutor)
  private val eventQueue = new LinkedBlockingQueue[Event]()

  @volatile
  private var closed = false

  Future {
    processQueuedEvents()
  }

  @tailrec
  private def processQueuedEvents(): Unit = {
    val event = eventQueue.poll(500, TimeUnit.MILLISECONDS)
    if (event == null) {
      if (closed) {
        actuallyClose()
      } else {
        processQueuedEvents()
      }
    } else {
      writers.foreach { wrt =>
        Try(wrt.handleEvent(event))
      }
      processQueuedEvents()
    }
  }

  override def closeFile(): Unit = {
    closed = true
  }

  private def actuallyClose(): Unit = {
    logger.debug(s"Closing PhysSimEventWriter of iteration $itNum")
    writers.foreach(wrt => Try(wrt.closeFile()))
    writerExecutor.shutdown()
  }

  override def handleEvent(event: Event): Unit = {
    eventQueue.put(event)
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    logger.debug(s"Iteration $itNum notified about beam shutdown")
    if (!writerExecutor.isShutdown) {
      logger.debug(s"Iteration $itNum executor is still running")
      writerExecutor.awaitTermination(5, TimeUnit.HOURS)
      logger.debug(s"executor $itNum is terminated")
    }
  }
}

object PhysSimEventWriter extends LazyLogging {
  type CommonEventWriter = BasicEventHandler with EventWriter
  val defaultFmt: BeamEventsFileFormats = BeamEventsFileFormats.CSV_GZ

  def apply(beamServices: BeamServices, eventsManager: EventsManager): PhysSimEventWriter = {
    val formats = beamServices.beamConfig.beam.physsim.events.fileOutputFormats.split(",").map { str =>
      val maybeFmt = BeamEventsFileFormats.from(str)
      if (maybeFmt.isPresent)
        maybeFmt.get()
      else {
        logger.warn(s"Could not get BeamEventsFileFormats from $str. Assigning default one which is $defaultFmt")
        defaultFmt
      }
    }
    val beamEventLogger = new BeamEventsLogger(
      beamServices,
      beamServices.matsimServices,
      eventsManager,
      beamServices.beamConfig.beam.physsim.events.eventsToWrite
    )
    val writers = formats.map(createWriter(beamServices, beamEventLogger, _))
    new PhysSimEventWriter(writers, beamServices.matsimServices.getIterationNumber)
  }

  def createWriter(
    beamServices: BeamServices,
    beamEventLogger: BeamEventsLogger,
    fmt: BeamEventsFileFormats
  ): CommonEventWriter = {
    val eventsFileBasePath = beamServices.matsimServices.getControlerIO
      .getIterationFilename(beamServices.matsimServices.getIterationNumber, "physSimEvents")
    val path = eventsFileBasePath + "." + fmt.getSuffix
    fmt match {
      case BeamEventsFileFormats.XML | BeamEventsFileFormats.XML_GZ =>
        val eventsSampling = beamServices.beamConfig.beam.physsim.eventsSampling
        val eventsForFullVersionOfVia = beamServices.beamConfig.beam.physsim.eventsForFullVersionOfVia
        // Yes, for XML we use ViaCompatible writer!
        new EventWriterXML_viaCompatible(path, eventsForFullVersionOfVia, eventsSampling)
      case BeamEventsFileFormats.CSV | BeamEventsFileFormats.CSV_GZ =>
        new BeamEventsWriterCSV(path, beamEventLogger, beamServices, null)
      case x => throw new NotImplementedError(s"There is no writer for the file format $x")
    }
  }
}
