package beam.physsim.jdeqsim

import beam.agentsim.events.handling._
import beam.analysis.via.EventWriterXML_viaCompatible
import beam.physsim.jdeqsim.PhysSimEventWriter.CommonEventWriter
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.events.handler.BasicEventHandler

import scala.util.Try

class PhysSimEventWriter(writers: Array[CommonEventWriter]) extends BasicEventHandler with EventWriter {
  override def closeFile(): Unit = {
    writers.foreach(wrt => Try(wrt.closeFile()))
  }

  override def handleEvent(event: Event): Unit = {
    writers.foreach { wrt =>
      Try(wrt.handleEvent(event))
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
        logger.warn(s"Could not get BeamEventsFileFormats from $str. Assigning default one which is ${defaultFmt}")
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
    new PhysSimEventWriter(writers)
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
      case x => throw new NotImplementedError(s"There is no writer for the file format ${x}")
    }
  }
}
