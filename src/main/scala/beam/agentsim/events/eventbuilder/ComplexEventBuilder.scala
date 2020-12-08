package beam.agentsim.events.eventbuilder

import com.typesafe.scalalogging.StrictLogging
import org.matsim.core.api.experimental.events.EventsManager

/**
  *  A complex event builder collects data to create a complex event, which is compatible with the MATSim Event Manager.
  * @param eventManager Event manager, which will process the event.
  */
abstract class ComplexEventBuilder(val eventManager: EventsManager) extends StrictLogging {

  /**
    * This method processes the collected information (message), and is responsible for filtering out irrelevant messages.
    * @param message
    */
  def handleMessage(message: Any)

  /**
    * This method logs end of iteration status internal to the event builder, which might be useful for simulation and debugging.
    */
  def logEndOfIterationStatus()

}
