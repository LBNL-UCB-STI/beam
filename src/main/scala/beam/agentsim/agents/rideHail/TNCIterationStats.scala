package beam.agentsim.agents.rideHail

import beam.agentsim.infrastructure.TAZTreeMap
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class TNCIterationStats(
  rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
  tazTreeMap: TAZTreeMap,
  timeBinSizeInSec: Double,
  numberOfTimeBins:Int
) {
  def getRideHailStatsInfo(coord: Coord, time: Double): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId.toString
    val timeBin = (time / timeBinSizeInSec).toInt
    rideHailStats.get(tazId).flatMap(ab => ab(timeBin))
  }

  def getWithDifferentMap(differentMap:mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]): TNCIterationStats ={
    TNCIterationStats(differentMap,tazTreeMap,timeBinSizeInSec,numberOfTimeBins)
  }

  def printMap()={
    println("TNCIterationStats:")
    rideHailStats.values.head.foreach(println)
  }
}

object TNCIterationStats{
  def merge(statsA:TNCIterationStats,statsB:TNCIterationStats):TNCIterationStats={
    val result=mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    val tazSet=statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    val numberOfTimeBins=statsA.rideHailStats.values.head.size

    for (taz <-tazSet){
      //val bufferA=statsA.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      //val bufferB=statsB.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      result.put(taz,mergeArrayBuffer(statsA.rideHailStats.get(taz).get,statsB.rideHailStats.get(taz).get))
    }

    statsA.getWithDifferentMap(result)
  }

  def mergeArrayBuffer(bufferA:ArrayBuffer[Option[RideHailStatsEntry]], bufferB:ArrayBuffer[Option[RideHailStatsEntry]]): ArrayBuffer[Option[RideHailStatsEntry]] ={
    val result=ArrayBuffer[Option[RideHailStatsEntry]]()

     for (i <- 0 until bufferA.size){
        bufferA(i) match {
          case Some(rideHailStatsEntryA) =>
            bufferB(i) match {
              case Some(rideHailStatsEntryB) =>
                result+=Some(rideHailStatsEntryA.getAverage(rideHailStatsEntryB))
              case None =>
                result+=Some(rideHailStatsEntryA)
            }
          case None =>
            bufferB(i) match {
              case Some(rideHailStatsEntryB) =>
                result+=Some(rideHailStatsEntryB)
              case None =>
                result+=None
            }
        }
     }

    result
  }


}
