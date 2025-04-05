package beam.agentsim.agents.ridehail

import beam.agentsim.events.SpaceTime

// TODO
// what functions does RHM need?

case class WaitingEvent(location: SpaceTime, waitingDuration: Double)

class LocationWaitingTimeMatrix(val waitingEvents: Set[WaitingEvent]) {

  /*
  TODO: if code is slow, implement version with bins (which has a larger memory foot print)

  timeBinDurationInSec:Double

  val waitingEventBins: ArrayBuffer[Set[WaitingEvent]] = new ArrayBuffer[Set[WaitingEvent]]()

  waitingEvents.foreach{waitingEvent: WaitingEvent =>
    waitingEventBins.
  }

   */

  def getWaitingEventsAtTime(time: Double): Set[WaitingEvent] = {
    waitingEvents.filter(waitingEvent =>
      time >= waitingEvent.location.time && time <= waitingEvent.location.time + waitingEvent.waitingDuration
    )
  }
}

class IterationHistory() {}

// TODO: collect location, when, waiting time info.
// TODO: collect location, when idling time.
