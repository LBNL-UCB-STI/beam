package beam.agentsim.agents.ridehail

import javax.inject.Inject

import scala.collection.mutable.ArrayBuffer

class RideHailIterationHistory @Inject() () {

  //val rideHailIterationHistory=scala.collection.mutable.ListBuffer( Map[String, ArrayBuffer[Option[RideHailStatsEntry]]])
  // TODO: put in RideHailStats class!
  // create methods in that class, which I need for my programming

  val rideHailIterationStatsHistory: ArrayBuffer[TNCIterationStats] = ArrayBuffer()

  def updateRideHailStats(stats: TNCIterationStats): Unit = {
    rideHailIterationStatsHistory += stats

    // trimm array buffer as we just need 2 elements
    if (rideHailIterationStatsHistory.size > 2) {
      rideHailIterationStatsHistory.remove(0)
    }
  }

  def oscillationAdjustedTNCIterationStats: Option[TNCIterationStats] = {
    if (rideHailIterationStatsHistory.size >= 2) {
      val lastElement = rideHailIterationStatsHistory.last
      val secondLastElement = rideHailIterationStatsHistory(rideHailIterationStatsHistory.size - 2)
      Some(TNCIterationStats.merge(lastElement, secondLastElement))
    } else {
      rideHailIterationStatsHistory.lastOption
    }
  }
}
