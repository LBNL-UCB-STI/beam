package beam.agentsim.agents.rideHail

import beam.agentsim.infrastructure.TAZTreeMap
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class TNCIterationStats(
  rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
  tazTreeMap: TAZTreeMap,
  timeBinSizeInSec: Double
) {
  def getRideHailStatsInfo(coord: Coord, time: Double): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId.toString
    val timeBin = (time / timeBinSizeInSec).toInt
    rideHailStats.get(tazId).flatMap(ab => ab(timeBin))
  }
}
