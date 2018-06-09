package beam.agentsim.agents.rideHail

import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.mobsim.jdeqsim.Vehicle
import org.matsim.vehicles
import org.matsim.vehicles.VehicleUtils

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class TNCIterationStats(
  rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
  tazTreeMap: TAZTreeMap,
  timeBinSizeInSec: Double,
  numberOfTimeBins:Int
) {
  def whichCoordToRepositionTo(vehiclesToReposition: Vector[RideHailingManager.RideHailingAgentLocation], repositionCircleRadisInMeters: Int): Vector[(Id[vehicles.Vehicle], Location)] = {

    // for all vehicles to reposition, group them by TAZ (k vehicles for a TAZ)
      // 1.) find all TAZ in radius
      // 2.) score them according to total waiting time
    //   3.) take top 3 and assign according to weights more or less to them
    // 4.)


    /*


    add inpt to method: tick, timeHorizonToConsiderInSecondsForIdleVehicles

    tazVehicleGroup= group vehicles by taz -> taz -> vehicles

    for each taz in tazVehicleGroup.key{

        for all tazInRadius(taz, repositionCircleRadisInMeters){

                 add scores for bins tick to    timeHorizonToConsiderInSecondsForIdleVehicles.waitingTimes

                 assign score to TAZ
        }

        scores = Vector((tazInRAdius,taz)






        -> assing to each vehicle in tazVehicleGroup(taz) the top best vehicles.

    }







     */





???
  }


  def getRideHailStatsInfo(coord: Coord, time: Double): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId.toString
    val timeBin = (time / timeBinSizeInSec).toInt
    rideHailStats.get(tazId).flatMap(ab => ab(timeBin))
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(startTime: Double,duration:Double): Vector[(TAZ,Double)] ={
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    ???
  }




  def getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles: TrieMap[Id[vehicles.Vehicle],
                                                    RideHailingManager.RideHailingAgentLocation], maxNumberOfVehiclesToReposition: Double,
                                                    tick: Double, timeHorizonToConsiderInSecondsForIdleVehicles: Int): Vector[RideHailingManager.RideHailingAgentLocation]={
    // #######start algorithm: only look at 20min horizon and those vehicles which are located in areas with high scores should be selected for repositioning
    // but don't take all of them, only take percentage wise - e.g. if scores are TAZ-A=50, TAZ-B=40, TAZ-3=10, then we would like to get more people from TAZ-A than from TAZ-B and C.
    // e.g. just go through 20min


    /*
    priorityQueue=(ordering by score, values are vehicles).
    for (vehicle <-idleVehicles){
    var idleScore=0
      for (t<-startTimeBin to timeHorizonToConsiderInSecondsForIdleVehicles_bin)
          val rideHailStatsEntry=getRideHailStatsInfo(t, vehicle.coor)
          idleScore+=rideHailStatsEntry.sumOfIdlingVehicles
      }
      priorityQueue.add(score, vehicle)
    }
    vehicles <- priorityQueue.takeHighestScores(maxNumberOfVehiclesToReposition)
    => this is result.
    */

    val priorityQueue: mutable.PriorityQueue[Vehiclelocationscores] = mutable.PriorityQueue[Vehiclelocationscores]()(VehiclelocationscoresOrdering)

    idleVehicles.foreach{
      case (vId, rhLoc) => {

        val startTimeBin = getTimeBin(tick)
        val endTimeBin = getTimeBin(timeHorizonToConsiderInSecondsForIdleVehicles)
        var idleScore = 0
        for(t <- startTimeBin to endTimeBin){

          val rideHailStatsEntry = getRideHailStatsInfo(rhLoc.currentLocation.loc, t)
          rideHailStatsEntry match {
            case Some(r) => {
              idleScore += r.sumOfIdlingVehicles.toInt
            }
            case _ =>
          }
        }

        val o = Vehiclelocationscores(vId, rhLoc, idleScore)
        priorityQueue.enqueue(o)

      }
    }


    val listOfLocations = (priorityQueue.take(maxNumberOfVehiclesToReposition.toInt).map {
      case (vls: Vehiclelocationscores) => {
        vls.rideHailingAgentLocation
      }
    }).toVector

    listOfLocations

    //
    // go through vehicles
    // those vehicles, which are located in areas with high number of idling time in future from now, should be moved
    // the longer the waiting time in future, the l
    // just look at smaller repositionings


  }



  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
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

case class Vehiclelocationscores(val vehicleId: Id[vehicles.Vehicle], val rideHailingAgentLocation: RideHailingManager.RideHailingAgentLocation, val score: Int)

object VehiclelocationscoresOrdering extends Ordering[Vehiclelocationscores]{

  def compare(vls1: Vehiclelocationscores, vls2: Vehiclelocationscores) = {
    vls1.score.compare(vls2.score)
  }
}