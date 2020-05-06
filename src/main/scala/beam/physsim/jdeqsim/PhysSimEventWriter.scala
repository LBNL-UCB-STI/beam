package beam.physsim.jdeqsim

import beam.agentsim.events.handling._
import beam.analysis.via.EventWriterXML_viaCompatible
import beam.physsim.jdeqsim.PhysSimEventWriter.CommonEventWriter
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
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

  def apply(
    beamConfig: BeamConfig,
    outputDirectoryHierarchy: OutputDirectoryHierarchy,
    eventsManager: EventsManager,
    iterationNumber: Int
  ): PhysSimEventWriter = {
    val formats = beamConfig.beam.physsim.events.fileOutputFormats.split(",").map { str =>
      val maybeFmt = BeamEventsFileFormats.from(str)
      if (maybeFmt.isPresent)
        maybeFmt.get()
      else {
        logger.warn(s"Could not get BeamEventsFileFormats from $str. Assigning default one which is $defaultFmt")
        defaultFmt
      }
    }
    val beamEventLogger = new BeamEventsLogger(
      beamConfig,
      outputDirectoryHierarchy,
      eventsManager,
      beamConfig.beam.physsim.events.eventsToWrite
    )
    val writers = formats.map(createWriter(beamConfig, outputDirectoryHierarchy, beamEventLogger, _, iterationNumber))
    new PhysSimEventWriter(writers)
  }

  def createWriter(
    beamConfig: BeamConfig,
    outputDirectoryHierarchy: OutputDirectoryHierarchy,
    beamEventLogger: BeamEventsLogger,
    fmt: BeamEventsFileFormats,
    iterationNumber: Int
  ): CommonEventWriter = {
    val eventsFileBasePath = outputDirectoryHierarchy.getIterationFilename(iterationNumber, "physSimEvents")
    val path = eventsFileBasePath + "." + fmt.getSuffix
    fmt match {
      case BeamEventsFileFormats.XML | BeamEventsFileFormats.XML_GZ =>
        val eventsSampling = beamConfig.beam.physsim.eventsSampling
        val eventsForFullVersionOfVia = beamConfig.beam.physsim.eventsForFullVersionOfVia
        // Yes, for XML we use ViaCompatible writer!
        new EventWriterXML_viaCompatible(path, eventsForFullVersionOfVia, eventsSampling)
      case BeamEventsFileFormats.CSV | BeamEventsFileFormats.CSV_GZ =>
        new BeamEventsWriterCSV(path, beamEventLogger, null)
      case x => throw new NotImplementedError(s"There is no writer for the file format $x")
    }
  }
}
