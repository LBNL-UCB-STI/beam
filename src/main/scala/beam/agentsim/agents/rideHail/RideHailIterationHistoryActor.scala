package beam.agentsim.agents.rideHail

import akka.actor.{Actor, Props}
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor.{AddTNCHistoryData, GetRideHailStats, UpdateHistoricWaitingTimes, UpdateRideHailStats}
import beam.sim.BeamServices
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class RideHailIterationHistoryActor(eventsManager: EventsManager, beamServices: BeamServices) extends Actor {

//  val tNCIterationsStatsCollector = new TNCIterationsStatsCollector(eventsManager, beamServices.beamConfig, self)

  //val rideHailIterationHistory=scala.collection.mutable.ListBuffer( Map[String, ArrayBuffer[Option[RideHailStatsEntry]]])
  // TODO: put in RideHailStats class!
  // create methods in that class, which I need for my programming


  // TODO: how get a reference of RideHailIterationHistoryActor to the rideHailManager?


  def receive = {
    case AddTNCHistoryData(_,_) =>  ??? // // receive message from TNCIterationsStatsCollector

    case GetRideHailStats() =>  //tNCIterationsStatsCollector.rideHailStats // received message from RideHailManager
      sender() ! UpdateHistoricWaitingTimes(null)

    case UpdateRideHailStats(rideHailStats) =>

    case message: String => {
      println(self + " received message [" + message + "] FROM " + sender)
    }

    case _      =>  ???
  }
}

object RideHailIterationHistoryActor {
  case class UpdateRideHailStats(rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]])

  case class AddTNCHistoryData(tncIdleTimes: Set[WaitingEvent], passengerWaitingTimes:Set[WaitingEvent])

  case class GetRideHailStats()

  case class UpdateHistoricWaitingTimes(historicWaitingTimes: HistoricWaitingTimes)

  case class HistoricWaitingTimes()

  def props(eventsManager: EventsManager, beamServices: BeamServices) = Props(new RideHailIterationHistoryActor(eventsManager, beamServices))
}
