package beam.agentsim.events.handling

import java.io.BufferedWriter

import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.events.handler.BasicEventHandler

trait BeamEventsWriterBase1 extends EventWriter with BasicEventHandler {

  var out: BufferedWriter
  var beamEventLogger: BeamEventsLogger
  var beamServices: BeamServices
  var eventTypeToLog: Class[_]


  override def handleEvent(event: Event): Unit = {
    if (beamEventLogger.shouldLogThisEventType(event.getClass) || eventTypeToLog.isInstance(event.getClass))
      writeEvent(event)
  }

  protected def writeEvent(event: Event): Unit
  protected def writeHeader(): Unit

}
