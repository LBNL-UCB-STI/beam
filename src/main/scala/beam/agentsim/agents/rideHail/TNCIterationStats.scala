package beam.agentsim.agents.rideHail

import java.lang.{Double => JDouble}

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.utils.DebugLib
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.util.{Pair => WeightPair}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case class TNCIterationStats(rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
                             tazTreeMap: TAZTreeMap,
                             timeBinSizeInSec: Double,
                             numberOfTimeBins: Int) {


  private val log = LoggerFactory.getLogger(classOf[TNCIterationStats])

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
                               repositionCircleRadiusInMeters: Double,
                               tick: Double, timeHorizonToConsiderForIdleVehiclesInSec: Double, beamServices: BeamServices):
  Vector[(Id[vehicles.Vehicle], Location)] = {

    // TODO: read from config and tune weights
    val distanceWeight = 1.0
    val waitingTimeWeight = 1.0
    val demandWeight = 1.0


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
      val scoredTAZInRadius = collection.mutable.ListBuffer[TazScore]()

      listOfTazInRadius.forEach { (tazInRadius) =>
        val startTimeBin = getTimeBin(tick)
        val endTimeBin = getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)

        val score = (startTimeBin to endTimeBin).map(
          getRideHailStatsInfo(tazInRadius.tazId, _) match {
            case Some(statsEntry) =>
              val distanceInMeters = beamServices.geo.distInMeters(taz.coord, tazInRadius.coord)
              val distanceScore = distanceWeight * 1 / (distanceInMeters + 1)
              val waitingTimeScore = waitingTimeWeight * Math.log(statsEntry.sumOfWaitingTimes + 1)
              val demandScore = demandWeight * Math.log(statsEntry.sumOfRequestedRides + 1)

              val res = distanceScore + waitingTimeScore + demandScore
              if (JDouble.isNaN(res)) {
                DebugLib.emptyFunctionForSettingBreakPoint()
              }

              // println(s"original score: $res")
              res

            case _ =>
              0
          }
        ).sum

        if (score > 0) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        scoredTAZInRadius += TazScore(tazInRadius, Math.exp(score))
      }

      val tazPriorityQueue = mutable.PriorityQueue[TazScore]()((tazScore1, tazScore2) => tazScore1.score.compare(tazScore2.score))

      val scoreExpSumOverAllTAZInRadius = scoredTAZInRadius.map(taz => taz.score).sum

      if (scoreExpSumOverAllTAZInRadius == 0) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }


      val mapping = new java.util.ArrayList[WeightPair[TAZ, java.lang.Double]]()
      scoredTAZInRadius.foreach { tazScore =>

        // println(s"score: ${tazScore.score/scoreExpSumOverAllTAZInRadius} - ${tazScore.score} - $scoreExpSumOverAllTAZInRadius")
        if (tazScore.score / scoreExpSumOverAllTAZInRadius == Double.NaN) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }


        mapping.add(new WeightPair(tazScore.taz, tazScore.score / scoreExpSumOverAllTAZInRadius))
      }

      val enumDistribution = new EnumeratedDistribution(mapping)

      val sample = enumDistribution.sample(vehicles.size)

      sample.foreach { x =>
        val z = x.asInstanceOf[TAZ]
        // println(z + " -> " +  scoredTAZInRadius.filter( y => y.taz==z))
      }

      val coords = for (taz <- sample; a = taz.asInstanceOf[TAZ].coord) yield a
      vehicles.zip(coords)
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
                                                    timeHorizonToConsiderForIdleVehiclesInSec: Double,
                                                    thresholdForMinimumNumberOfIdlingVehicles: Int): Vector[RideHailingAgentLocation] = {

    val priorityQueue = mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) => vls1.score.compare(vls2.score))

    // TODO: group by TAZ to avoid evaluation twice

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

    priorityQueue.take(maxNumberOfVehiclesToReposition.toInt).filter(vehicleLocationScores => vehicleLocationScores.score > thresholdForMinimumNumberOfIdlingVehicles).map(_.rideHailingAgentLocation).toVector
  }


  def demandRatioInCircleToOutside(vehiclesToReposition: Vector[RideHailingManager.RideHailingAgentLocation], circleSize: Double, tick: Double, timeWindowSizeInSecForDecidingAboutRepositioning: Double): Double = {
    import scala.collection.JavaConverters._
    val startTime = tick

    if (circleSize==Double.PositiveInfinity){
        DebugLib.emptyFunctionForSettingBreakPoint()
    }


    val endTime = tick + timeWindowSizeInSecForDecidingAboutRepositioning
    val listOfTazInRadius = vehiclesToReposition.flatMap(vehicle => tazTreeMap.getTAZInRadius(vehicle.currentLocation.loc, circleSize).asScala.map(_.tazId)).toSet
    val demandInCircle = listOfTazInRadius.map(getAggregatedRideHailStats(_, startTime, endTime).sumOfRequestedRides).sum
    val demandAll = getAggregatedRideHailStatsAllTAZ(startTime, endTime).sumOfRequestedRides
    val result = if(demandAll > 0) demandInCircle.toDouble / demandAll.toDouble else Double.PositiveInfinity
    result
  }


  val maxRadiusInMeters=10 * 1000


  def getUpdatedCircleSize(vehiclesToReposition: Vector[RideHailingManager.RideHailingAgentLocation], circleRadiusInMeters: Double, tick: Double, timeWindowSizeInSecForDecidingAboutRepositioning: Double, minReachableDemandByVehiclesSelectedForReposition:Double, allowIncreasingRadiusIfMostDemandOutside:Boolean): Double ={
    var updatedRadius=circleRadiusInMeters

    while (vehiclesToReposition.size>0 && allowIncreasingRadiusIfMostDemandOutside && updatedRadius<maxRadiusInMeters && demandRatioInCircleToOutside(vehiclesToReposition, updatedRadius, tick, timeWindowSizeInSecForDecidingAboutRepositioning) < minReachableDemandByVehiclesSelectedForReposition) {
      updatedRadius = updatedRadius * 2
    }

    if (circleRadiusInMeters!=updatedRadius){
      log.debug(s"search radius for repositioning algorithm increased: $updatedRadius")
    }

    updatedRadius
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

  def getAggregatedRideHailStats(coord: Coord, startTime: Double, endTime: Double): RideHailStatsEntry = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getAggregatedRideHailStats(tazId, startTime, endTime)
  }

  def getAggregatedRideHailStats(tazId: Id[TAZ], startTime: Double, endTime: Double): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    RideHailStatsEntry.aggregate((startTimeBin to endTimeBin).map(getRideHailStatsInfo(tazId, _)).toList)
  }

  def getAggregatedRideHailStatsAllTAZ(startTime: Double, endTime: Double): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    RideHailStatsEntry.aggregate((startTimeBin to endTimeBin).flatMap(timeBin => rideHailStats.collect { case (_, stats) => stats(timeBin) }).toList)
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(startTime: Double, duration: Double): Vector[(TAZ, Double)] = {
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    ???
  }

  def logMap(): Unit = {
    log.debug("TNCIterationStats:")

    var columns = "index\t\t aggregate \t\t"
    val aggregates: ArrayBuffer[RideHailStatsEntry] = ArrayBuffer.fill(numberOfTimeBins)(RideHailStatsEntry(0, 0, 0))
    rideHailStats.foreach(rhs => {
      columns = columns + rhs._1 + "\t\t"
    })
    log.debug(columns)

    for (i <- 1 until numberOfTimeBins) {
      columns = ""
      rideHailStats.foreach(rhs => {
        val arrayBuffer = rhs._2
        val entry = arrayBuffer(i).getOrElse(RideHailStatsEntry(0, 0, 0))

        aggregates(i) = aggregates(i).copy(aggregates(i).sumOfRequestedRides + entry.sumOfRequestedRides,
          aggregates(i).sumOfWaitingTimes + entry.sumOfWaitingTimes,
          aggregates(i).sumOfIdlingVehicles + entry.sumOfIdlingVehicles)

        columns = columns + entry + "\t\t"
      })
      columns = i + "\t\t" + aggregates(i) + "\t\t" + columns
      log.debug(columns)
    }

  }
}

object TNCIterationStats {
  def merge(statsA: TNCIterationStats, statsB: TNCIterationStats): TNCIterationStats = {
    val result = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    val tazSet = statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    for (taz <- tazSet) {
      //val bufferA=statsA.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      //val bufferB=statsB.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      //result.put(taz, mergeArrayBuffer(statsA.rideHailStats.getOrElse(taz,ArrayBuffer[Option[RideHailStatsEntry]]()) , statsB.rideHailStats.getOrElse(taz,ArrayBuffer[Option[RideHailStatsEntry]]())))


      result.put(taz, mergeArrayBuffer(statsA.rideHailStats.get(taz), statsB.rideHailStats.get(taz)))
    }

    statsA.getWithDifferentMap(result)
  }

  def mergeArrayBuffer(bufferA: Option[ArrayBuffer[Option[RideHailStatsEntry]]], bufferB: Option[ArrayBuffer[Option[RideHailStatsEntry]]]): ArrayBuffer[Option[RideHailStatsEntry]] = {
    val result = ArrayBuffer[Option[RideHailStatsEntry]]()

    bufferA match {
      case Some(bufferA) =>

        bufferB match {
          case Some(bufferB) =>
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

          case None =>
            bufferA

        }

      case None =>
        bufferB match {
          case Some(bufferB) =>
            bufferB
          case None =>
            result
        }
    }

  }
}

case class VehicleLocationScores(rideHailingAgentLocation: RideHailingAgentLocation, score: Double)

case class TazScore(taz: TAZ, score: Double)
