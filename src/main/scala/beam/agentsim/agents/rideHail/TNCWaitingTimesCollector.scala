package beam.agentsim.agents.rideHail

import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler

class TNCWaitingTimesCollector(eventsManager: EventsManager,) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  // timeBins -> number OfTimeBins input

  //numberOfRides: -> passengers =1 (sum of rides)
  //customerWaitTime -> sum and average

  //idleTimes = count in each bin according to how much time remaining
  // agent arrives in a time 1000 and leaves at time 2000
  // bin Size=100 -> count as idle in 10 bins (from 1000 to 2000)
  //idleTime[TAZId,binNumber] // bin 10, 11, 12,...19 we do +1



  eventsManager.addHandler(this)

  def getTNCIdlingTimes():Set[WaitingEvent]={
    ???
  }

  def getTNCPassengerWaitingTimes():Set[WaitingEvent]={
    ???
  }

  def tellHistoryToRideHailIterationHistoryActor(): Unit ={
    // TODO: send message to actor with collected data
  }

  override def handleEvent(event: Event): Unit = {

  }
}