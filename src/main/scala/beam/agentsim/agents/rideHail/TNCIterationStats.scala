package beam.agentsim.agents.rideHail

import java.lang.{Double => JDouble}

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.utils.DebugLib
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.ml.clustering.{Clusterable, KMeansPlusPlusClusterer}
import org.apache.commons.math3.util.{Pair => WeightPair}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case class TNCIterationStats(
                              rideHailStats: Map[String, List[Option[RideHailStatsEntry]]],
                              tazTreeMap: TAZTreeMap,
                              timeBinSizeInSec: Double,
                              numberOfTimeBins: Int) {

  private val log = LoggerFactory.getLogger(classOf[TNCIterationStats])
  //logMap()

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
  def reposition(
                  vehiclesToReposition: Vector[RideHailingAgentLocation],
                  repositionCircleRadiusInMeters: Double,
                  tick: Double,
                  timeHorizonToConsiderForIdleVehiclesInSec: Double,
                  beamServices: BeamServices): Vector[(Id[vehicles.Vehicle], Location)] = {

    // log.debug("whichCoordToRepositionTo.start=======================")
    val repositioningConfig = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes

    val repositioningMethod = repositioningConfig.repositioningMethod // (TOP_SCORES | weighedKMeans)
    val keepMaxTopNScores = repositioningConfig.keepMaxTopNScores
    val minScoreThresholdForRespositioning = repositioningConfig.minScoreThresholdForRespositioning // helps weed out unnecessary repositioning
    
    val distanceWeight = repositioningConfig.distanceWeight
    val waitingTimeWeight = repositioningConfig.waitingTimeWeight
    val demandWeight = repositioningConfig.demandWeight

    if (tick>36000){
      DebugLib.emptyFunctionForSettingBreakPoint()
    }


    val tazVehicleMap = mutable.Map[TAZ, ListBuffer[Id[vehicles.Vehicle]]]()

    // Vehicle Grouping in Taz
    vehiclesToReposition.foreach { rhaLoc =>
      val vehicleTaz = tazTreeMap.getTAZ(rhaLoc.currentLocation.loc.getX,
        rhaLoc.currentLocation.loc.getY)

      tazVehicleMap.get(vehicleTaz) match {
        case Some(lov: ListBuffer[Id[vehicles.Vehicle]]) =>
          lov += rhaLoc.vehicleId

        case None =>
          val lov = ListBuffer[Id[vehicles.Vehicle]]()
          lov += rhaLoc.vehicleId
          tazVehicleMap.put(vehicleTaz, lov)
      }
    }

    val result = (for ((taz, vehicles) <- tazVehicleMap) yield {

      val listOfTazInRadius = tazTreeMap.getTAZInRadius(
        taz.coord.getX,
        taz.coord.getY,
        repositionCircleRadiusInMeters)

      //  log.debug(s"number of TAZ in radius around current TAZ (${taz.tazId}): ${listOfTazInRadius.size()}")

      if (listOfTazInRadius.size() > 0) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }


      var scoredTAZInRadius =
        mutable.PriorityQueue[TazScore]()((vls1, vls2) =>
          vls1.score.compare(vls2.score))

     // val scoredTAZInRadius = mutable.ListBuffer[TazScore]()

      listOfTazInRadius.forEach { (tazInRadius) =>
        val startTimeBin = getTimeBin(tick)
        val endTimeBin =
          getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)
        val distanceInMeters =
          beamServices.geo.distInMeters(taz.coord, tazInRadius.coord)

        val distanceScore = -1 * distanceWeight * Math.pow(distanceInMeters,2) / Math.pow(distanceInMeters + 1000.0,2)

        val score = (startTimeBin to endTimeBin)
          .map(
            getRideHailStatsInfo(tazInRadius.tazId, _) match {
              case Some(statsEntry) =>

                val waitingTimeScore = waitingTimeWeight * Math.pow(statsEntry.sumOfWaitingTimes,2) /   Math.pow(statsEntry.sumOfWaitingTimes + 1000.0,2)

                val demandScore = demandWeight *  Math.pow(statsEntry.getDemandEstimate,2) / Math.pow(statsEntry.getDemandEstimate + 10.0,2)

                val finalScore = waitingTimeScore + demandScore + distanceScore

              //  log.debug(s"(${tazInRadius.tazId})-score: distanceScore($distanceScore) + waitingTimeScore($waitingTimeScore) + demandScore($demandScore) = $res")

                if (waitingTimeScore > 0) {
                  DebugLib.emptyFunctionForSettingBreakPoint()
                }

                if (statsEntry.getDemandEstimate>0){

                  DebugLib.emptyFunctionForSettingBreakPoint()
                }

                finalScore

              case _ =>
                0
            }
          ).sum

//        if (score > 0) {
//          DebugLib.emptyFunctionForSettingBreakPoint()
//        }

        scoredTAZInRadius += TazScore(tazInRadius, score)
      }


      // filter top N scores
      // ignore scores smaller than minScoreThresholdForRespositioning
      val tmp = scoredTAZInRadius.take(keepMaxTopNScores).filter(tazScore => tazScore.score > minScoreThresholdForRespositioning && tazScore.score > 0)

      if (tmp.size > 1) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }

      scoredTAZInRadius = tmp

      // TODO: add WEIGHTED_KMEANS as well

      val vehicleToCoordAssignment = if (scoredTAZInRadius.size > 0) {
        val coords = if (repositioningMethod.equalsIgnoreCase("TOP_SCORES") || scoredTAZInRadius.size <= vehicles.size) {
          // Not using
          val scoreExpSumOverAllTAZInRadius =
            scoredTAZInRadius.map(taz => taz.score).sum
          //scoredTAZInRadius.map(taz => Math.exp(taz.score)).sum

          if (scoreExpSumOverAllTAZInRadius == 0) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }

          val mapping = new java.util.ArrayList[WeightPair[TAZ, java.lang.Double]]()
          scoredTAZInRadius.foreach { tazScore =>

            //log.debug(s"taz(${tazScore.taz.tazId})-score: ${ Math.exp(tazScore.score)} / ${scoreExpSumOverAllTAZInRadius} = ${Math.exp(tazScore.score) / scoreExpSumOverAllTAZInRadius}")

            mapping.add(
              new WeightPair(tazScore.taz,
                Math.exp(tazScore.score) / scoreExpSumOverAllTAZInRadius))
                //Math.exp(tazScore.score) / scoreExpSumOverAllTAZInRadius))
          }

          val enumDistribution = new EnumeratedDistribution(mapping)
          val sample = enumDistribution.sample(vehicles.size)

          for (taz <- sample; a = taz.asInstanceOf[TAZ].coord) yield a
        } else if (repositioningMethod.equalsIgnoreCase("KMEANS")) {
          val clusterInput = scoredTAZInRadius.map(t => new LocationWrapper(t.taz.coord))

          val clusterSize = if (clusterInput.size < vehicles.size) clusterInput.size else vehicles.size
          val clusterer = new KMeansPlusPlusClusterer[LocationWrapper](clusterSize, 1000)
          val clusterResults = clusterer.cluster(clusterInput.toVector.asJava)
          clusterResults.asScala.map(c => new Coord(c.getCenter.getPoint()(0), c.getCenter.getPoint()(1))).toArray
        } else {
          throw new RuntimeException(s"unknown repositioningMethod: $repositioningMethod")
          ???
        }
        vehicles.zip(coords)
      } else {
        Vector()
      }

      if (vehicles.size > 1 && tick > 10000) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }

      // TODO: from top taz which remain after filtering, apply kmeans to them (unweighted, as we don't have weighted implementation available yet)

      // TODO: add kmeans approach here with number of vehicle clusters. (switch between top n scores and cluster).

      vehicleToCoordAssignment
    }).flatten.toVector

    if (result.size > 0) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }
    // log.debug("whichCoordToRepositionTo.end=======================")

    result
  }


  def getVehiclesCloseToIdlingAreas(idleVehicles: Vector[RideHailingAgentLocation],
                                    maxNumberOfVehiclesToReposition: Double,
                                    tick: Double,
                                    timeHorizonToConsiderForIdleVehiclesInSec: Double,
                                    thresholdForMinimumNumberOfIdlingVehicles: Int, beamServices: BeamServices)
  : Vector[RideHailingAgentLocation] ={
    var priorityQueue =
      mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) =>
        vls1.score.compare(vls2.score))

    val maxDistanceInMeters=500

    val startTimeBin = getTimeBin(tick)
    val endTimeBin = getTimeBin(
      tick + timeHorizonToConsiderForIdleVehiclesInSec)

    if (startTimeBin>2){
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    val tmp=rideHailStats.map(tazId => (tazId._1,getAggregatedRideHailStats(Id.create(tazId._1, classOf[TAZ]),tick,tick + timeHorizonToConsiderForIdleVehiclesInSec)))

    val idleTAZs=tmp.filter( t => t._2.sumOfIdlingVehicles>=thresholdForMinimumNumberOfIdlingVehicles)




    for (rhLoc <- idleVehicles) {
      var idleScore = 0L

      for (taz <-tazTreeMap.getTAZInRadius(rhLoc.currentLocation.loc.getX,rhLoc.currentLocation.loc.getY,maxDistanceInMeters).asScala){
        if (idleTAZs.contains(taz.tazId.toString)){
          idleScore = idleScore + idleTAZs.get(taz.tazId.toString).get.sumOfIdlingVehicles
        }
      }
      priorityQueue.enqueue(VehicleLocationScores(rhLoc, idleScore))
    }



    priorityQueue= priorityQueue.filter(vehicleLocationScores =>
      vehicleLocationScores.score >= thresholdForMinimumNumberOfIdlingVehicles)
/*

//TODO: figure out issue with this code, why ERROR:
more rideHailVehicle interruptions in process than should be possible: rideHailVehicle-22 -> further errors surpressed (debug later if this is still relevant)
03:34:58.103 [beam-actor-system-akka.actor.default-dispatcher-9] ERROR beam.agentsim.agents.rideHail.RideHailingManager -
when enabled

    if (!priorityQueue.isEmpty){

      val scoreSum=priorityQueue.map( x=> x.score).sum

      val mapping = new java.util.ArrayList[WeightPair[RideHailingAgentLocation, java.lang.Double]]()
      priorityQueue.foreach { vehicleLocationScore =>

        mapping.add(
          new WeightPair(vehicleLocationScore.rideHailingAgentLocation,
            vehicleLocationScore.score / scoreSum))
      }

      val enumDistribution = new EnumeratedDistribution(mapping)
      val sample = enumDistribution.sample(idleVehicles.size)

      (for (rideHailingAgentLocation <- sample; a = rideHailingAgentLocation.asInstanceOf[RideHailingAgentLocation]) yield a).toVector
    } else {
      Vector()
    }

    */

    val head = priorityQueue
      .take(maxNumberOfVehiclesToReposition.toInt)

    //printTAZForVehicles(idleVehicles)

    head.map(_.rideHailingAgentLocation)
      .toVector
  }


  // #######start algorithm: only look at 20min horizon and those vehicles which are located in areas with high scores should be selected for repositioning
  // but don't take all of them, only take percentage wise - e.g. if scores are TAZ-A=50, TAZ-B=40, TAZ-3=10, then we would like to get more people from TAZ-A than from TAZ-B and C.
  // e.g. just go through 20min

  // go through vehicles
  // those vehicles, which are located in areas with high number of idling time in future from now, should be moved
  // the longer the waiting time in future, the l
  // just look at smaller repositioning
  def getVehiclesWhichAreBiggestCandidatesForIdling(
                                                     idleVehicles: Vector[RideHailingAgentLocation],
                                                     maxNumberOfVehiclesToReposition: Double,
                                                     tick: Double,
                                                     timeHorizonToConsiderForIdleVehiclesInSec: Double,
                                                     thresholdForMinimumNumberOfIdlingVehicles: Int)
  : Vector[RideHailingAgentLocation] = {

    // TODO: convert to non sorted, as priority queue not needed anymore
    var priorityQueue =
      mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) =>
        vls1.score.compare(vls2.score))

    // TODO: group by TAZ to avoid evaluation multiple times?

    for (rhLoc <- idleVehicles) {

      val startTimeBin = getTimeBin(tick)
      val endTimeBin = getTimeBin(
        tick + timeHorizonToConsiderForIdleVehiclesInSec)

      val taz = tazTreeMap.getTAZ(rhLoc.currentLocation.loc.getX,
        rhLoc.currentLocation.loc.getY)

      val idleScore = (startTimeBin to endTimeBin)
        .map(
          getRideHailStatsInfo(taz.tazId, _) match {
            case Some(statsEntry) =>
              if (statsEntry.sumOfIdlingVehicles>0){
                DebugLib.emptyFunctionForSettingBreakPoint()
              }


              statsEntry.sumOfIdlingVehicles

            case _ =>
              0
          }
        )
        .sum

      priorityQueue.enqueue(VehicleLocationScores(rhLoc, idleScore))
    }

/*
    priorityQueue= priorityQueue.filter(vehicleLocationScores =>
      vehicleLocationScores.score >= thresholdForMinimumNumberOfIdlingVehicles)

    if (!priorityQueue.isEmpty){

    val scoreSum=priorityQueue.map( x=> x.score).sum

    val mapping = new java.util.ArrayList[WeightPair[RideHailingAgentLocation, java.lang.Double]]()
    priorityQueue.foreach { vehicleLocationScore =>

      mapping.add(
        new WeightPair(vehicleLocationScore.rideHailingAgentLocation,
          vehicleLocationScore.score / scoreSum))
    }

    val enumDistribution = new EnumeratedDistribution(mapping)
    val sample = enumDistribution.sample(idleVehicles.size)

    (for (rideHailingAgentLocation <- sample; a = rideHailingAgentLocation.asInstanceOf[RideHailingAgentLocation]) yield a).toVector
    } else {
      Vector()
    }
*/
    // TODO: replace below code with above - was getting stuck perhaps due to empty set?


    val head = priorityQueue
      .take(maxNumberOfVehiclesToReposition.toInt)

    //printTAZForVehicles(idleVehicles)

    head
      .filter(vehicleLocationScores =>
        vehicleLocationScores.score >= thresholdForMinimumNumberOfIdlingVehicles)
      .map(_.rideHailingAgentLocation)
      .toVector
  }

  def printTAZForVehicles(rideHailingAgentLocations: Vector[RideHailingAgentLocation]) = {
    log.debug("vehicle located at TAZs:")
    val vehicleToTAZ=rideHailingAgentLocations.foreach( x=> log.debug(s"s${x.vehicleId} -> ${tazTreeMap.getTAZ(x.currentLocation.loc.getX,
      x.currentLocation.loc.getY).tazId}"))
  }

  def demandRatioInCircleToOutside(
                                    vehiclesToReposition: Vector[RideHailingManager.RideHailingAgentLocation],
                                    circleSize: Double,
                                    tick: Double,
                                    timeWindowSizeInSecForDecidingAboutRepositioning: Double): Double = {
    import scala.collection.JavaConverters._
    val startTime = tick

    if (circleSize == Double.PositiveInfinity) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    val endTime = tick + timeWindowSizeInSecForDecidingAboutRepositioning
    val listOfTazInRadius = vehiclesToReposition
      .flatMap(
        vehicle =>
          tazTreeMap
            .getTAZInRadius(vehicle.currentLocation.loc, circleSize)
            .asScala
            .map(_.tazId))
      .toSet
    val demandInCircle = listOfTazInRadius
      .map(
        getAggregatedRideHailStats(_, startTime, endTime).getDemandEstimate)
      .sum
    val demandAll =
      getAggregatedRideHailStatsAllTAZ(startTime, endTime).getDemandEstimate
    val result =
      if (demandAll > 0) demandInCircle.toDouble / demandAll.toDouble
      else Double.PositiveInfinity
    result
  }

  val maxRadiusInMeters = 10 * 1000

  def getUpdatedCircleSize(
                            vehiclesToReposition: Vector[RideHailingManager.RideHailingAgentLocation],
                            circleRadiusInMeters: Double,
                            tick: Double,
                            timeWindowSizeInSecForDecidingAboutRepositioning: Double,
                            minReachableDemandByVehiclesSelectedForReposition: Double,
                            allowIncreasingRadiusIfMostDemandOutside: Boolean): Double = {
    var updatedRadius = circleRadiusInMeters

    while (vehiclesToReposition.size > 0 && allowIncreasingRadiusIfMostDemandOutside && updatedRadius < maxRadiusInMeters && demandRatioInCircleToOutside(
      vehiclesToReposition,
      updatedRadius,
      tick,
      timeWindowSizeInSecForDecidingAboutRepositioning) < minReachableDemandByVehiclesSelectedForReposition) {
      updatedRadius = updatedRadius * 2
    }

    if (circleRadiusInMeters != updatedRadius) {
      log.debug("search radius for repositioning algorithm increased: {}", updatedRadius)
    }

    updatedRadius
  }

  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }

  def getWithDifferentMap(
                           differentMap: Map[String, List[Option[RideHailStatsEntry]]])
  : TNCIterationStats = {
    TNCIterationStats(differentMap,
      tazTreeMap,
      timeBinSizeInSec,
      numberOfTimeBins)
  }

  def getRideHailStatsInfo(coord: Coord,
                           timeBin: Int): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getRideHailStatsInfo(tazId, timeBin)
  }

  def getRideHailStatsInfo(tazId: Id[TAZ],
                           timeBin: Int): Option[RideHailStatsEntry] = {

    val tmp=rideHailStats.get(tazId.toString)
      tmp.flatMap(ab => ab(timeBin))
  }

  def getAggregatedRideHailStats(coord: Coord,
                                 startTime: Double,
                                 endTime: Double): RideHailStatsEntry = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getAggregatedRideHailStats(tazId, startTime, endTime)
  }

  def getAggregatedRideHailStats(tazId: Id[TAZ],
                                 startTime: Double,
                                 endTime: Double): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    RideHailStatsEntry.aggregate(
      (startTimeBin to endTimeBin).map(getRideHailStatsInfo(tazId, _)).toList)
  }

  def getCoordinatesWithRideHailStatsEntry(
                                            startTime: Double,
                                            endTime: Double): ListBuffer[(Coord, RideHailStatsEntry)] = {
    var result = collection.mutable.ListBuffer[(Coord, RideHailStatsEntry)]()

    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    for (tazIdString <- rideHailStats.keySet) {
      val rideHailStatsEntry = getAggregatedRideHailStats(
        Id.create(tazIdString, classOf[TAZ]),
        startTime,
        endTime)

      result += ((tazTreeMap.getTAZ(tazIdString).get.coord, rideHailStatsEntry))
    }

    result
  }

  def getAggregatedRideHailStatsAllTAZ(startTime: Double,
                                       endTime: Double): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    RideHailStatsEntry.aggregate(
      (startTimeBin to endTimeBin)
        .flatMap(timeBin =>
          rideHailStats.collect { case (_, stats) => stats(timeBin) })
        .toList)
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(
                                         startTime: Double,
                                         duration: Double): Vector[(TAZ, Double)] = {
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    ???
  }

  def logMap(): Unit = {
    log.debug("TNCIterationStats:")

    var columns = "index\t\t aggregate \t\t"
    val aggregates: ArrayBuffer[RideHailStatsEntry] =
      ArrayBuffer.fill(numberOfTimeBins)(RideHailStatsEntry.empty)
    rideHailStats.foreach(rhs => {
      columns = columns + rhs._1 + "\t\t"
    })
    log.debug(columns)

    for (i <- 1 until numberOfTimeBins) {
      columns = ""
      rideHailStats.foreach(rhs => {
        val arrayBuffer = rhs._2
        val entry = arrayBuffer(i).getOrElse(RideHailStatsEntry.empty)

        aggregates(i) = aggregates(i).aggregate(entry)

        columns = columns + entry + "\t\t"
      })
      columns = i + "\t\t" + aggregates(i) + "\t\t" + columns
      log.debug(columns)
    }

  }
}

object TNCIterationStats {
  def merge(statsA: TNCIterationStats,
            statsB: TNCIterationStats): TNCIterationStats = {

    val tazSet = statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    val result = tazSet.map { taz =>
      taz -> mergeArrayBuffer(statsA.rideHailStats.get(taz),
        statsB.rideHailStats.get(taz))
    }.toMap

    statsA.copy(rideHailStats = result)
  }

  def mergeArrayBuffer(bufferA: Option[List[Option[RideHailStatsEntry]]],
                       bufferB: Option[List[Option[RideHailStatsEntry]]])
  : List[Option[RideHailStatsEntry]] = {

    (bufferA, bufferB) match {
      case (Some(bA), Some(bB)) =>
        bA.zip(bB).map {
          case (Some(a), Some(b)) => Some(a average b)
          case (None, bs@Some(_)) => bs
          case (as@Some(_), None) => as
          case (None, None) => None
        }
      case (None, Some(bB)) => bB
      case (Some(bA), None) => bA
      case (None, None) => List()
    }
  }
}

case class VehicleLocationScores(
                                  rideHailingAgentLocation: RideHailingAgentLocation,
                                  score: Double)

case class TazScore(taz: TAZ, score: Double)

class LocationWrapper(location: Location) extends Clusterable {

  val points: Array[Double] = Array(location.getX, location.getY)

  override def getPoint: Array[Double] = points
}
