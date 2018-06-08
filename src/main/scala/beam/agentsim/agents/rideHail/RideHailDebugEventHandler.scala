package beam.agentsim.agents.rideHail

import beam.utils.DebugLib
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler

class RideHailDebugEventHandler(eventsManager: EventsManager) extends BasicEventHandler {


  eventsManager.addHandler(this)

  override def handleEvent(event: Event): Unit = {
    // if peson enters ride hail vehicle then number of passengers >0 in ride hail vehicle







      DebugLib.emptyFunctionForSettingBreakPoint()

  }


  override def reset(iteration: Int): Unit = {


  }

}
