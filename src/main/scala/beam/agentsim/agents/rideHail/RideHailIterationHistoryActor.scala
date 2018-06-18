package beam.agentsim.agents.rideHail

import akka.actor.{Actor, Props}
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor._
import beam.sim.BeamServices
import beam.utils.DebugLib
import org.matsim.core.api.experimental.events.EventsManager

class RideHailIterationHistoryActor(eventsManager: EventsManager,
                                    beamServices: BeamServices)
    extends Actor {

  val tNCIterationsStatsCollector =
    new TNCIterationsStatsCollector(eventsManager, beamServices, self)

  //val rideHailIterationHistory=scala.collection.mutable.ListBuffer( Map[String, ArrayBuffer[Option[RideHailStatsEntry]]])
  // TODO: put in RideHailStats class!
  // create methods in that class, which I need for my programming

  // TODO: how get a reference of RideHailIterationHistoryActor to the rideHailManager?

  val rideHailIterationStatsHistory =
    scala.collection.mutable.ArrayBuffer[TNCIterationStats]()

  def oszilationAdjustedTNCIterationStats(): Option[TNCIterationStats] = {
    if (rideHailIterationStatsHistory.size >= 2) {
      val lastElement = rideHailIterationStatsHistory.last
      val secondLastElement = rideHailIterationStatsHistory(
        rideHailIterationStatsHistory.size - 2)
      Some(TNCIterationStats.merge(lastElement, secondLastElement))
    } else {
      rideHailIterationStatsHistory.lastOption
    }
  }

  def receive = {

    case GetCurrentIterationRideHailStats => //tNCIterationsStatsCollector.rideHailStats // received message from RideHailManager
      val stats = oszilationAdjustedTNCIterationStats()
      //  stats.foreach(_.printMap())
      sender() ! stats
    //sender() ! UpdateHistoricWaitingTimes(null)

    case UpdateRideHailStats(stats) =>
      rideHailIterationStatsHistory += stats

      // trimm array buffer as we just need 2 elements
      if (rideHailIterationStatsHistory.size > 2) {
        rideHailIterationStatsHistory.remove(0)
      }

    case _ =>
      DebugLib.emptyFunctionForSettingBreakPoint()
    // TODO: add logger!
  }
}

object RideHailIterationHistoryActor {

  case class UpdateRideHailStats(rideHailStats: TNCIterationStats)

  case class AddTNCHistoryData(tncIdleTimes: Set[WaitingEvent],
                               passengerWaitingTimes: Set[WaitingEvent])

  case object GetCurrentIterationRideHailStats

  case class UpdateHistoricWaitingTimes(
      historicWaitingTimes: HistoricWaitingTimes)

  case class HistoricWaitingTimes()

  case class CollectRideHailStats()

  def props(eventsManager: EventsManager, beamServices: BeamServices) =
    Props(new RideHailIterationHistoryActor(eventsManager, beamServices))
}
