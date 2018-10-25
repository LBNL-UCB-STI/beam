package beam.agentsim.events.handling

import java.io.BufferedWriter

import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.events.handler.BasicEventHandler

/**
  * @author Bhavya Latha Bandaru.
  * A generic event writer interface.
  */
trait BeamEventsWriterBase1 extends EventWriter with BasicEventHandler {

  var out: BufferedWriter
  var beamEventLogger: BeamEventsLogger
  var beamServices: BeamServices
  var eventTypeToLog: Class[_]

  /**
    * Handles writing the given event to an external file.
    * @param event event to be written
    */
  override def handleEvent(event: Event): Unit = {
    if (beamEventLogger.shouldLogThisEventType(event.getClass) || eventTypeToLog.isInstance(event.getClass))
      writeEvent(event)
  }

  /**
    * Writes the event to an external file.
    * @param event event to be written
    */
  protected def writeEvent(event: Event): Unit

  /**
    * Appends header content to the file.
    */
  protected def writeHeader(): Unit

}
