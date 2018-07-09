package beam.agentsim.agents.rideHail

import akka.actor.Actor
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler

class HistoricWaitingTimes(){

}


class RideHailIterationHistoryActor extends Actor{
  def receive: PartialFunction[Any, Unit] = {
    case AddTNCHistoryData(_,_) =>  ??? // // receive message from TNCWaitingTimesCollector
    case GetWaitingTimes() =>  ??? // received message from RideHailManager
      sender() ! UpdateHistoricWaitingTimes(null)
    case _      =>  ???
  }
}


case class AddTNCHistoryData(tncIdleTimes: Set[WaitingEvent], passengerWaitingTimes:Set[WaitingEvent])


case class GetWaitingTimes()


case class UpdateHistoricWaitingTimes(historicWaitingTimes: HistoricWaitingTimes)