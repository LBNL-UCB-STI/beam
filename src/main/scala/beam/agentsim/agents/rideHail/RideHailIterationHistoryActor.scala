package beam.agentsim.agents.rideHail

import akka.actor.{Actor, Props}
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Network
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.vehicles.Vehicles

class HistoricWaitingTimes(){

}


class RideHailIterationHistoryActor(eventsManager: EventsManager, beamServices: BeamServices) extends Actor {

  val tNCIterationsStatsCollector = new TNCIterationsStatsCollector(eventsManager, beamServices.beamConfig, self)

  def receive = {
    case AddTNCHistoryData(_,_) =>  ??? // // receive message from TNCIterationsStatsCollector
    case GetWaitingTimes() =>  //tNCIterationsStatsCollector.rideHailStats // received message from RideHailManager
      sender() ! UpdateHistoricWaitingTimes(null)
    case _      =>  ???
  }
}

object RideHailIterationHistoryActor {
  def props(eventsManager: EventsManager, beamServices: BeamServices) = Props(new RideHailIterationHistoryActor(eventsManager, beamServices))
}
case class AddTNCHistoryData(tncIdleTimes: Set[WaitingEvent], passengerWaitingTimes:Set[WaitingEvent])


case class GetWaitingTimes()


case class UpdateHistoricWaitingTimes(historicWaitingTimes: HistoricWaitingTimes)