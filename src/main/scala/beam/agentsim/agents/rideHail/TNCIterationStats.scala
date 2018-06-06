package beam.agentsim.agents.rideHail

import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class TNCIterationStats(val rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]) {

def getRideHailStatsInfo(coord:Coord, time:Double):Option[RideHailStatsEntry]={

  // get TAZID from coord -> TNCIterationsStatsCollector

  ???
}





}
