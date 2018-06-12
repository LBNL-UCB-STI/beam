package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case class TNCIterationStats(rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
                             tazTreeMap: TAZTreeMap,
                             timeBinSizeInSec: Double,
                             numberOfTimeBins: Int) {


  /**
    * for all vehicles to reposition, group them by TAZ (k vehicles for a TAZ)
    * 1.) find all TAZ in radius
    * 2.) score them according to total waiting time
    * 3.) take top 3 and assign according to weights more or less to them
    * 4.)
    *
    * use tazTreeMap.getTAZInRadius(x,y,radius).
    * //vehiclesToReposition: v1, v2, v3, v4, v5
    * // taz1 -> v1,v2 (list)
    * // taz2 -> v3,v4,v5 (list)
    * add input to method: tick, timeHorizonToConsiderInSecondsForIdleVehicles
    * tazVehicleGroup= group vehicles by taz -> taz -> vehicles
    * for each taz in tazVehicleGroup.key{
    * for all tazInRadius(taz, repositionCircleRadiusInMeters){
    * add scores for bins tick to    timeHorizonToConsiderInSecondsForIdleVehicles.waitingTimes
    * assign score to TAZ
    * }
    * scores = Vector((tazInRadius,score)    taz1 -> score1, taz2 -> score2, etc. (best scores are taz9, taz10) -> assign taz9.coord to v1 and taz10.coord to v2
    * -> assign to each vehicle in tazVehicleGroup(taz) the top best vehicles.
    * }
    */
  def whichCoordToRepositionTo(vehiclesToReposition: Vector[RideHailingAgentLocation],
                               repositionCircleRadiusInMeters: Int,
                               tick: Double, timeHorizonToConsiderForIdleVehiclesInSec: Int, beamServices: BeamServices):
  Vector[(Id[vehicles.Vehicle], Location)] = {

    // TODO: read from config and tune weights
    val distanceWeight=1.0
    val waitingTimeWeight=1.0
    val demandWeight=1.0


    val tazVehicleMap = mutable.Map[TAZ, ListBuffer[Id[vehicles.Vehicle]]]()

    // Vehicle Grouping in Taz
    vehiclesToReposition.foreach { rhaLoc =>
      val vehicleTaz = tazTreeMap.getTAZ(rhaLoc.currentLocation.loc.getX, rhaLoc.currentLocation.loc.getY)

      tazVehicleMap.get(vehicleTaz) match {
        case Some(lov: ListBuffer[Id[vehicles.Vehicle]]) =>
          lov += rhaLoc.vehicleId

        case None =>
          val lov = ListBuffer[Id[vehicles.Vehicle]]()
          lov += rhaLoc.vehicleId
          tazVehicleMap.put(vehicleTaz, lov)
      }
    }

    val result = for ((taz, vehicles) <- tazVehicleMap) yield {

      val listOfTazInRadius = tazTreeMap.getTAZInRadius(taz.coord.getX, taz.coord.getY, repositionCircleRadiusInMeters)
      val scoredTAZInRadius= collection.mutable.ListBuffer[TazScore]()

      listOfTazInRadius.forEach { (tazInRadius) =>
        val startTimeBin = getTimeBin(tick)
        val endTimeBin = getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)

        val score = (startTimeBin to endTimeBin).map(
          getRideHailStatsInfo(tazInRadius.tazId, _) match {
            case Some(statsEntry) =>
              val distanceInMeters=beamServices.geo.distInMeters(taz.coord, tazInRadius.coord)
              val distanceScore = distanceWeight * distanceInMeters
              val waitingTimeScore = waitingTimeWeight * statsEntry.sumOfWaitingTimes
              val demandScore = demandWeight * statsEntry.sumOfRequestedRides
              (distanceScore + waitingTimeScore + demandScore)

            case _ =>
              0
          }
        ).sum

        scoredTAZInRadius+=TazScore(tazInRadius, score)
      }

      val scoreSumOverAllTAZInRadius=scoredTAZInRadius.map(taz => taz.score).sum

      val tazPriorityQueue = mutable.PriorityQueue[TazScore]()((tazScore1, tazScore2) => tazScore1.score.compare(tazScore2.score))

      scoredTAZInRadius.foreach{tazScore =>
        tazPriorityQueue.enqueue(TazScore(tazScore.taz, tazScore.score/scoreSumOverAllTAZInRadius))
      }

      vehicles.zip(tazPriorityQueue.take(vehicles.size).map(_.taz.coord))
    }

    result.flatten.toVector
  }

  // #######start algorithm: only look at 20min horizon and those vehicles which are located in areas with high scores should be selected for repositioning
  // but don't take all of them, only take percentage wise - e.g. if scores are TAZ-A=50, TAZ-B=40, TAZ-3=10, then we would like to get more people from TAZ-A than from TAZ-B and C.
  // e.g. just go through 20min

  // go through vehicles
  // those vehicles, which are located in areas with high number of idling time in future from now, should be moved
  // the longer the waiting time in future, the l
  // just look at smaller repositioning
  def getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles: TrieMap[Id[vehicles.Vehicle], RideHailingAgentLocation],
                                                    maxNumberOfVehiclesToReposition: Double,
                                                    tick: Double,
                                                    timeHorizonToConsiderForIdleVehiclesInSec: Int): Vector[RideHailingAgentLocation] = {

    val priorityQueue = mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) => vls1.score.compare(vls2.score))

    for ((_, rhLoc) <- idleVehicles) {

      val startTimeBin = getTimeBin(tick)
      val endTimeBin = getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)

      val idleScore = (startTimeBin to endTimeBin).map(
        getRideHailStatsInfo(rhLoc.currentLocation.loc, _) match {
          case Some(statsEntry) =>
            statsEntry.sumOfIdlingVehicles

          case _ =>
            0
        }
      ).sum

      priorityQueue.enqueue(VehicleLocationScores(rhLoc, idleScore))
    }

    priorityQueue.take(maxNumberOfVehiclesToReposition.toInt).map(_.rideHailingAgentLocation).toVector
  }

  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }

  def getWithDifferentMap(differentMap: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]): TNCIterationStats = {
    TNCIterationStats(differentMap, tazTreeMap, timeBinSizeInSec, numberOfTimeBins)
  }


  def getRideHailStatsInfo(coord: Coord, timeBin: Int): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getRideHailStatsInfo(tazId, timeBin)
  }

  def getRideHailStatsInfo(tazId: Id[TAZ], timeBin: Int): Option[RideHailStatsEntry] = {

    rideHailStats.get(tazId.toString).flatMap(ab => ab(timeBin))
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(startTime: Double, duration: Double): Vector[(TAZ, Double)] = {
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    ???
  }

  def printMap(): Unit = {
    println("TNCIterationStats:")
    rideHailStats.values.head.foreach(println)
  }
}

object TNCIterationStats {
  def merge(statsA: TNCIterationStats, statsB: TNCIterationStats): TNCIterationStats = {
    val result = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    val tazSet = statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    for (taz <- tazSet) {
      //val bufferA=statsA.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      //val bufferB=statsB.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      result.put(taz, mergeArrayBuffer(statsA.rideHailStats(taz), statsB.rideHailStats(taz)))
    }

    statsA.getWithDifferentMap(result)
  }

  def mergeArrayBuffer(bufferA: ArrayBuffer[Option[RideHailStatsEntry]], bufferB: ArrayBuffer[Option[RideHailStatsEntry]]): ArrayBuffer[Option[RideHailStatsEntry]] = {
    val result = ArrayBuffer[Option[RideHailStatsEntry]]()

    for (i <- bufferA.indices) {
      bufferA(i) match {

        case Some(rideHailStatsEntryA) =>

          bufferB(i) match {

            case Some(rideHailStatsEntryB) =>
              result += Some(rideHailStatsEntryA.average(rideHailStatsEntryB))

            case None =>
              result += Some(rideHailStatsEntryA)
          }

        case None =>

          bufferB(i) match {

            case Some(rideHailStatsEntryB) =>
              result += Some(rideHailStatsEntryB)

            case None =>
              result += None
          }
      }
    }

    result
  }
}

case class VehicleLocationScores(rideHailingAgentLocation: RideHailingAgentLocation, score: Double)

case class TazScore(taz: TAZ, score: Double)
