package beam.agentsim.agents.rideHail

import akka.actor.{Actor, Props}
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor._
import beam.sim.BeamServices
import beam.utils.DebugLib
import org.matsim.core.api.experimental.events.EventsManager

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class RideHailIterationHistoryActor(eventsManager: EventsManager, beamServices: BeamServices) extends Actor {

  val tNCIterationsStatsCollector = new TNCIterationsStatsCollector(eventsManager, beamServices.beamConfig, self)

  //val rideHailIterationHistory=scala.collection.mutable.ListBuffer( Map[String, ArrayBuffer[Option[RideHailStatsEntry]]])
  // TODO: put in RideHailStats class!
  // create methods in that class, which I need for my programming


  // TODO: how get a reference of RideHailIterationHistoryActor to the rideHailManager?

  val rideHailIterationStatsHistory=scala.collection.mutable.ListBuffer[TNCIterationStats]()


  def receive = {
  //  case AddTNCHistoryData(_,_) =>  ??? // // receive message from TNCIterationsStatsCollector

  //  case CollectRideHailStats =>
  //    tNCIterationsStatsCollector.tellHistoryToRideHailIterationHistoryActorAndReset()

    case GetCurrentIterationRideHailStats =>  //tNCIterationsStatsCollector.rideHailStats // received message from RideHailManager
      sender() ! rideHailIterationStatsHistory.lastOption
      //sender() ! UpdateHistoricWaitingTimes(null)

    case UpdateRideHailStats(stats) =>
      rideHailIterationStatsHistory+=stats
   // case message: String => {
   //   println(self + " received message [" + message + "] FROM " + sender)
    //}

    case _      =>

      DebugLib.emptyFunctionForSettingBreakPoint()
      // TODO: add logger!
  }
}

object RideHailIterationHistoryActor {
  case class UpdateRideHailStats(rideHailStats: TNCIterationStats)

  case class AddTNCHistoryData(tncIdleTimes: Set[WaitingEvent], passengerWaitingTimes:Set[WaitingEvent])

  case object GetCurrentIterationRideHailStats

  case class UpdateHistoricWaitingTimes(historicWaitingTimes: HistoricWaitingTimes)

  case class HistoricWaitingTimes()

  case class CollectRideHailStats()

  def props(eventsManager: EventsManager, beamServices: BeamServices) = Props(new RideHailIterationHistoryActor(eventsManager, beamServices))
}
