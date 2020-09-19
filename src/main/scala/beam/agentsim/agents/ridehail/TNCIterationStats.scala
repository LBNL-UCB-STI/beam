package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.TNCIterationStats._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.utils.DebugLib
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.ml.clustering.{Clusterable, KMeansPlusPlusClusterer}
import org.apache.commons.math3.util.{Pair => WeightPair}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.reflect.ClassTag

case class TNCIterationStats(
  rideHailStats: Map[String, List[Option[RideHailStatsEntry]]],
  tazTreeMap: TAZTreeMap,
  timeBinSizeInSec: Double,
  numberOfTimeBins: Int
) extends LazyLogging {
  //logMap()
  private val maxRadiusInMeters = 10 * 1000

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
    vehiclesToReposition: Vector[RideHailAgentLocation],
    repositionCircleRadiusInMeters: Double,
    tick: Double,
    timeHorizonToConsiderForIdleVehiclesInSec: Double,
    beamServices: BeamServices
  ): Vector[(Id[BeamVehicle], Location)] = {

    // logger.debug("whichCoordToRepositionTo.start=======================")
    val repositioningConfig =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes

    val repositioningMethod = repositioningConfig.repositioningMethod // (TOP_SCORES | weighedKMeans)
    val keepMaxTopNScores = repositioningConfig.keepMaxTopNScores
    val minScoreThresholdForRepositioning =
      repositioningConfig.minScoreThresholdForRepositioning // helps weed out unnecessary repositioning

    val distanceWeight = repositioningConfig.distanceWeight
    val waitingTimeWeight = repositioningConfig.waitingTimeWeight
    val demandWeight = repositioningConfig.demandWeight

    val startTimeBin = getTimeBin(tick)
    val endTimeBin = getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)

    if (tick > BREAKPOINT_TIMEOUT_IN_MILLIS) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    val tazVehicleMap = mutable.Map[TAZ, ListBuffer[Id[BeamVehicle]]]()

    // Vehicle Grouping in Taz
    vehiclesToReposition.foreach { rhaLoc =>
      val vehicleTaz =
        tazTreeMap.getTAZ(rhaLoc.currentLocationUTM.loc.getX, rhaLoc.currentLocationUTM.loc.getY)

      tazVehicleMap.get(vehicleTaz) match {
        case Some(lov: ListBuffer[Id[BeamVehicle]]) =>
          lov += rhaLoc.vehicleId

        case None =>
          val lov = ListBuffer[Id[BeamVehicle]]()
          lov += rhaLoc.vehicleId
          tazVehicleMap.put(vehicleTaz, lov)
      }
    }

    val result = (for ((taz, vehicles) <- tazVehicleMap) yield {

      val listOfTazInRadius =
        tazTreeMap.getTAZInRadius(taz.coord.getX, taz.coord.getY, repositionCircleRadiusInMeters)

      //  logger.debug(s"number of TAZ in radius around current TAZ (${taz.tazId}): ${listOfTazInRadius.size()}")

      if (listOfTazInRadius.size() > 0) {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }

      var scoredTAZInRadius =
        mutable.PriorityQueue[TazScore]()((vls1, vls2) => vls1.score.compare(vls2.score))

      // val scoredTAZInRadius = mutable.ListBuffer[TazScore]()

      listOfTazInRadius.forEach { tazInRadius =>
        val distanceInMeters =
          beamServices.geo.distUTMInMeters(taz.coord, tazInRadius.coord)

        val distanceScore = -1 * distanceWeight * Math
          .pow(distanceInMeters, 2) /
        Math.pow(distanceInMeters + 1000.0, 2)

        val score = (startTimeBin to endTimeBin)
          .map(
            getRideHailStatsInfo(tazInRadius.tazId, _) match {
              case Some(statsEntry) =>
                val waitingTimeScore = waitingTimeWeight * Math
                  .pow(statsEntry.sumOfWaitingTimes, 2) /
                Math.pow(statsEntry.sumOfWaitingTimes + 1000.0, 2)

                val demandScore = demandWeight * Math
                  .pow(statsEntry.getDemandEstimate, 2) /
                Math.pow(statsEntry.getDemandEstimate + 10.0, 2)

                val finalScore = waitingTimeScore + demandScore + distanceScore

                //  logger.debug(s"(${tazInRadius.tazId})-score: distanceScore($distanceScore) + waitingTimeScore($waitingTimeScore) + demandScore($demandScore) = $res")

                if (waitingTimeScore > 0) {
                  DebugLib.emptyFunctionForSettingBreakPoint()
                }

                if (statsEntry.getDemandEstimate > 0) {
                  DebugLib.emptyFunctionForSettingBreakPoint()
                }

                finalScore

              case _ =>
                0
            }
          )
          .sum

        //        if (score > 0) {
        //          DebugLib.emptyFunctionForSettingBreakPoint()
        //        }

        scoredTAZInRadius += TazScore(tazInRadius, score)
      }

      // filter top N scores
      // ignore scores smaller than minScoreThresholdForRepositioning
      val topScored = takeTopN(scoredTAZInRadius, keepMaxTopNScores)
        .filter(
          tazScore => tazScore.score > minScoreThresholdForRepositioning && tazScore.score > 0
        )

      // TODO: add WEIGHTED_KMEANS as well

      val vehicleToCoordAssignment = if (topScored.nonEmpty) {
        val coords =
          if (repositioningMethod
                .equalsIgnoreCase("TOP_SCORES") || topScored.length <= vehicles.size) {
            // Not using
            val scoreExpSumOverAllTAZInRadius =
              topScored.map(taz => taz.score).sum
            //scoredTAZInRadius.map(taz => exp(taz.score)).sum

            if (scoreExpSumOverAllTAZInRadius == 0) {
              DebugLib.emptyFunctionForSettingBreakPoint()
            }

            val mapping =
              new java.util.ArrayList[WeightPair[TAZ, java.lang.Double]]()
            topScored.foreach { tazScore =>
              //logger.debug(s"taz(${tazScore.taz.tazId})-score: ${ exp(tazScore.score)} / ${scoreExpSumOverAllTAZInRadius} = ${exp(tazScore.score) / scoreExpSumOverAllTAZInRadius}")

              mapping.add(
                new WeightPair(
                  tazScore.taz,
                  Math.exp(tazScore.score) / scoreExpSumOverAllTAZInRadius
                )
              )
              //exp(tazScore.score) / scoreExpSumOverAllTAZInRadius))
            }

            val enumDistribution = new EnumeratedDistribution(mapping)
            val sample = enumDistribution.sample(vehicles.size, Array[TAZ]())

            sample.map(_.coord)
          } else if (repositioningMethod.equalsIgnoreCase("KMEANS")) {
            val clusterInput = topScored.map(t => new LocationWrapper(t.taz.coord))

            val clusterSize =
              if (clusterInput.length < vehicles.size) clusterInput.length
              else vehicles.size
            val kMeans =
              new KMeansPlusPlusClusterer[LocationWrapper](clusterSize, 1000)
            val clusterResults = kMeans.cluster(clusterInput.toVector.asJava)
            clusterResults.asScala
              .map(c => new Coord(c.getCenter.getPoint()(0), c.getCenter.getPoint()(1)))
              .toArray
          } else {
            throw new RuntimeException(s"unknown repositioningMethod: $repositioningMethod")
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

    // logger.debug("whichCoordToRepositionTo.end=======================")

    result
  }

  // #######start algorithm: only look at 20min horizon and those vehicles which are located in areas with high scores should be selected for repositioning
  // but don't take all of them, only take percentage wise - e.g. if scores are TAZ-A=50, TAZ-B=40, TAZ-3=10, then we would like to get more people from TAZ-A than from TAZ-B and C.
  // e.g. just go through 20min

  def getVehiclesCloseToIdlingAreas(
    idleVehicles: Vector[RideHailAgentLocation],
    maxNumberOfVehiclesToReposition: Double,
    tick: Double,
    timeHorizonToConsiderForIdleVehiclesInSec: Double,
    thresholdForMinimumNumberOfIdlingVehicles: Int,
    beamServices: BeamServices
  ): Vector[RideHailAgentLocation] = {
    var priorityQueue =
      mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) => vls1.score.compare(vls2.score))

    val maxDistanceInMeters = 500

    val tmp = rideHailStats.map(
      tazId =>
        (
          tazId._1,
          getAggregatedRideHailStats(
            Id.create(tazId._1, classOf[TAZ]),
            tick,
            tick + timeHorizonToConsiderForIdleVehiclesInSec
          )
      )
    )

    val idleTAZs =
      tmp.filter(t => t._2.sumOfIdlingVehicles >= thresholdForMinimumNumberOfIdlingVehicles)

    for (rhLoc <- idleVehicles) {
      var idleScore = 0L

      for (taz <- tazTreeMap
             .getTAZInRadius(
               rhLoc.currentLocationUTM.loc.getX,
               rhLoc.currentLocationUTM.loc.getY,
               maxDistanceInMeters
             )
             .asScala) {
        if (idleTAZs.contains(taz.tazId.toString)) {
          idleScore = idleScore + idleTAZs(taz.tazId.toString).sumOfIdlingVehicles
        }
      }
      priorityQueue.enqueue(VehicleLocationScores(rhLoc, idleScore))
    }

    priorityQueue = priorityQueue.filter(
      vehicleLocationScores => vehicleLocationScores.score >= thresholdForMinimumNumberOfIdlingVehicles
    )
    /*

    //TODO: figure out issue with this code, why ERROR:
    more rideHailVehicle interruptions in process than should be possible: rideHailVehicle-22 -> further errors surpressed (debug later if this is still relevant)
    03:34:58.103 [beam-actor-system-akka.actor.default-dispatcher-9] ERROR beam.agentsim.agents.rideHail.RideHailManager -
    when enabled

        if (!priorityQueue.isEmpty){

          val scoreSum= priorityQueue.map(_.score).sum

          val mapping =
          priorityQueue.map (vehicleLocationScore =>
              new WeightPair(vehicleLocationScore.rideHailAgentLocation,
                new java.lang.Double(vehicleLocationScore.score / scoreSum))).toList.asJava

          val enumDistribution = new EnumeratedDistribution(mapping)
          val sample = enumDistribution.sample(maxNumberOfVehiclesToReposition.toInt, Array[RideHailAgentLocation]())

          sample.toVector
        } else {
          Vector()
        }

     */

    val head = takeTopN(priorityQueue, maxNumberOfVehiclesToReposition.toInt)

    //printTAZForVehicles(idleVehicles)

    head.map(_.rideHailAgentLocation).toVector
  }

  // go through vehicles
  // those vehicles, which are located in areas with high number of idling time in future from now, should be moved
  // the longer the waiting time in future, the l
  // just look at smaller repositioning
  def getVehiclesWhichAreBiggestCandidatesForIdling(
    idleVehicles: Vector[RideHailAgentLocation],
    maxNumberOfVehiclesToReposition: Double,
    tick: Double,
    timeHorizonToConsiderForIdleVehiclesInSec: Double,
    thresholdForMinimumNumberOfIdlingVehicles: Int
  ): Vector[RideHailAgentLocation] = {

    // TODO: convert to non sorted, as priority queue not needed anymore
    val priorityQueue =
      mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) => vls1.score.compare(vls2.score))

    // TODO: group by TAZ to avoid evaluation multiple times?

    for (rhLoc <- idleVehicles) {

      val startTimeBin = getTimeBin(tick)
      val endTimeBin = getTimeBin(tick + timeHorizonToConsiderForIdleVehiclesInSec)

      val taz = tazTreeMap.getTAZ(rhLoc.currentLocationUTM.loc.getX, rhLoc.currentLocationUTM.loc.getY)

      val idleScore = (startTimeBin to endTimeBin)
        .map(
          getRideHailStatsInfo(taz.tazId, _) match {
            case Some(statsEntry) =>
              if (statsEntry.sumOfIdlingVehicles > 0) {
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

        val mapping = new java.util.ArrayList[WeightPair[RideHailAgentLocation, java.lang.Double]]()
        priorityQueue.foreach { vehicleLocationScore =>

          mapping.add(
            new WeightPair(vehicleLocationScore.rideHailAgentLocation,
              vehicleLocationScore.score / scoreSum))
        }

        val enumDistribution = new EnumeratedDistribution(mapping)
        val sample = enumDistribution.sample(idleVehicles.size)

        (for (rideHailAgentLocation <- sample; a = rideHailAgentLocation.asInstanceOf[RideHailAgentLocation]) yield a).toVector
        } else {
          Vector()
        }
     */
    // TODO: replace below code with above - was getting stuck perhaps due to empty set?

    val head = takeTopN(priorityQueue, maxNumberOfVehiclesToReposition.toInt)

    //printTAZForVehicles(idleVehicles)

    head
      .filter(
        vehicleLocationScores => vehicleLocationScores.score >= thresholdForMinimumNumberOfIdlingVehicles
      )
      .map(_.rideHailAgentLocation)
      .toVector
  }

  def printTAZForVehicles(rideHailAgentLocations: Vector[RideHailAgentLocation]): Unit = {
    logger.debug("vehicle located at TAZs:")
    rideHailAgentLocations.foreach(
      x =>
        logger.debug(
          "s{} -> {}",
          x.vehicleId,
          tazTreeMap.getTAZ(x.currentLocationUTM.loc.getX, x.currentLocationUTM.loc.getY).tazId
      )
    )
  }

  def takeTopN[T](pq: mutable.PriorityQueue[T], n: Int)(implicit ct: ClassTag[T]): Array[T] = {
    if (n <= 0 || pq.isEmpty) Array.empty[T]
    else {
      var i: Int = 0
      val resSize: Int = if (pq.size > n) n else pq.size
      val result = Array.ofDim[T](resSize)
      while (i < resSize) {
        val top = pq.dequeue()
        result.update(i, top)
        i += 1
      }
      result
    }
  }

  def getUpdatedCircleSize(
    vehiclesToReposition: Vector[RideHailAgentLocation],
    circleRadiusInMeters: Double,
    tick: Double,
    timeWindowSizeInSecForDecidingAboutRepositioning: Double,
    minReachableDemandByVehiclesSelectedForReposition: Double,
    allowIncreasingRadiusIfMostDemandOutside: Boolean
  ): Double = {
    var updatedRadius = circleRadiusInMeters

    while (vehiclesToReposition.nonEmpty && allowIncreasingRadiusIfMostDemandOutside && updatedRadius < maxRadiusInMeters && demandRatioInCircleToOutside(
             vehiclesToReposition,
             updatedRadius,
             tick,
             timeWindowSizeInSecForDecidingAboutRepositioning
           ) < minReachableDemandByVehiclesSelectedForReposition) {
      updatedRadius = updatedRadius * 2
    }

    if (!circleRadiusInMeters.equals(updatedRadius)) {
      logger.debug("search radius for repositioning algorithm increased: {}", updatedRadius)
    }

    updatedRadius
  }

  def demandRatioInCircleToOutside(
    vehiclesToReposition: Vector[RideHailAgentLocation],
    circleSize: Double,
    tick: Double,
    timeWindowSizeInSecForDecidingAboutRepositioning: Double
  ): Double = {
    import scala.collection.JavaConverters._
    val startTime = tick

    if (circleSize.isPosInfinity) {
      DebugLib.emptyFunctionForSettingBreakPoint()
    }

    val endTime = tick + timeWindowSizeInSecForDecidingAboutRepositioning
    val listOfTazInRadius = vehiclesToReposition
      .flatMap(
        vehicle =>
          tazTreeMap
            .getTAZInRadius(vehicle.currentLocationUTM.loc, circleSize)
            .asScala
            .map(_.tazId)
      )
      .toSet
    val demandInCircle = listOfTazInRadius
      .map(getAggregatedRideHailStats(_, startTime, endTime).getDemandEstimate)
      .sum
    val demandAll =
      getAggregatedRideHailStatsAllTAZ(startTime, endTime).getDemandEstimate
    val result =
      if (demandAll > 0) demandInCircle.toDouble / demandAll.toDouble
      else Double.PositiveInfinity
    result
  }

  def getAggregatedRideHailStatsAllTAZ(startTime: Double, endTime: Double): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)
    val stats = (startTimeBin to endTimeBin)
      .flatMap(timeBin => rideHailStats.collect { case (_, _stats) => _stats(timeBin) })
      .toList
    RideHailStatsEntry.aggregate(stats)
  }

  def getAggregatedRideHailStats(
    tazId: Id[TAZ],
    startTime: Double,
    endTime: Double
  ): RideHailStatsEntry = {
    val startTimeBin = getTimeBin(startTime)
    val endTimeBin = getTimeBin(endTime)

    RideHailStatsEntry.aggregate(
      (startTimeBin to endTimeBin).map(getRideHailStatsInfo(tazId, _)).toList
    )
  }

  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }

  def getRideHailStatsInfo(tazId: Id[TAZ], timeBin: Int): Option[RideHailStatsEntry] = {

    val tazBins = rideHailStats.get(tazId.toString)
    tazBins.flatMap(bins => bins(timeBin))
  }

  def getWithDifferentMap(
    differentMap: Map[String, List[Option[RideHailStatsEntry]]]
  ): TNCIterationStats = {
    TNCIterationStats(differentMap, tazTreeMap, timeBinSizeInSec, numberOfTimeBins)
  }

  def getRideHailStatsInfo(coord: Coord, timeBin: Int): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getRideHailStatsInfo(tazId, timeBin)
  }

  def getAggregatedRideHailStats(
    coord: Coord,
    startTime: Double,
    endTime: Double
  ): RideHailStatsEntry = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getAggregatedRideHailStats(tazId, startTime, endTime)
  }

  def getCoordinatesWithRideHailStatsEntry(
    startTime: Double,
    endTime: Double
  ): ListBuffer[(Coord, RideHailStatsEntry)] = {
    var result = collection.mutable.ListBuffer[(Coord, RideHailStatsEntry)]()

    for (tazIdString <- rideHailStats.keySet) {
      val rideHailStatsEntry =
        getAggregatedRideHailStats(Id.create(tazIdString, classOf[TAZ]), startTime, endTime)

      result += ((tazTreeMap.getTAZ(tazIdString).get.coord, rideHailStatsEntry))
    }

    result
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(
    startTime: Double,
    duration: Double
  ): Vector[(TAZ, Double)] = {
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    null
  }

  def logMap(): Unit = {
    logger.debug("TNCIterationStats:")

    var columns = "index\t\t aggregate \t\t"
    val aggregates: ArrayBuffer[RideHailStatsEntry] =
      ArrayBuffer.fill(numberOfTimeBins)(RideHailStatsEntry.empty)
    rideHailStats.foreach(rhs => {
      columns = columns + rhs._1 + "\t\t"
    })
    logger.debug(columns)

    for (i <- 1 until numberOfTimeBins) {
      columns = ""
      rideHailStats.foreach(rhs => {
        val arrayBuffer = rhs._2
        val entry = arrayBuffer(i).getOrElse(RideHailStatsEntry.empty)

        aggregates(i) = aggregates(i).aggregate(entry)

        columns = columns + entry + "\t\t"
      })
      columns = i + "\t\t" + aggregates(i) + "\t\t" + columns
      logger.debug(columns)
    }

  }
}

object TNCIterationStats {
  private val BREAKPOINT_TIMEOUT_IN_MILLIS = 36000

  def merge(statsA: TNCIterationStats, statsB: TNCIterationStats): TNCIterationStats = {

    val tazSet = statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    val result = tazSet.map { taz =>
      taz -> mergeArrayBuffer(statsA.rideHailStats.get(taz), statsB.rideHailStats.get(taz))
    }.toMap

    statsA.copy(rideHailStats = result)
  }

  def mergeArrayBuffer(
    bufferA: Option[List[Option[RideHailStatsEntry]]],
    bufferB: Option[List[Option[RideHailStatsEntry]]]
  ): List[Option[RideHailStatsEntry]] = {

    (bufferA, bufferB) match {
      case (Some(bA), Some(bB)) =>
        bA.zip(bB).map {
          case (Some(a), Some(b))   => Some(a average b)
          case (None, bs @ Some(_)) => bs
          case (as @ Some(_), None) => as
          case (None, None)         => None
        }
      case (None, Some(bB)) => bB
      case (Some(bA), None) => bA
      case (None, None)     => List()
    }
  }
}

case class VehicleLocationScores(rideHailAgentLocation: RideHailAgentLocation, score: Double)

case class TazScore(taz: TAZ, score: Double)

class LocationWrapper(location: Location) extends Clusterable {

  val points: Array[Double] = Array(location.getX, location.getY)

  override def getPoint: Array[Double] = points
}
