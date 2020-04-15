package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.taz.H3TAZ
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.CAR
import beam.router.skim.Skims
import beam.sim.BeamServices
import beam.utils.{ActivitySegment, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.util.Random

class InverseSquareDistanceRepositioningFactor(
  val beamServices: BeamServices,
  val rideHailManager: RideHailManager
) extends RepositioningManager(beamServices, rideHailManager)
    with LazyLogging {

  val h3taz: H3TAZ = beamServices.beamScenario.h3taz

  // When we have all activities, we can make `sensitivityOfRepositioningToDemand` in the range from [0, 1] to make it easer to calibrate
  // If sensitivityOfRepositioningToDemand = 1, it means all vehicles reposition all the time
  // sensitivityOfRepositioningToDemand = 0, means no one reposition
  private val cfg =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.inverseSquareDistanceRepositioningFactor
  val rndGen: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val rng: MersenneTwister = new MersenneTwister(beamServices.beamConfig.matsim.modules.global.randomSeed) // Random.org

  val sensitivityOfRepositioningToDistance: Double =
    if (cfg.sensitivityOfRepositioningToDistance >= 1) 1.0
    else if (cfg.sensitivityOfRepositioningToDistance <= 0) 0.0
    else cfg.sensitivityOfRepositioningToDistance
  // TBD this is hardcoded (50 km), maybe it should be inferred from hexagon clusters
  // it can be the maximum OD distance or the average
  val maxSensitivityToDistance: Double = 50000
  val sensitivityToDistance: Double = 1 / ((1 - sensitivityOfRepositioningToDistance) * maxSensitivityToDistance + 1000)
  val sensitivityToDemand: Double = cfg.sensitivityOfRepositioningToDemand
  val predictionHorizonBin: Int = cfg.predictionHorizon / repositionTimeout
  private val activitySegment: ActivitySegment = {
    ProfilingUtils.timed(s"Build ActivitySegment with bin size $repositionTimeout", x => logger.info(x)) {
      ActivitySegment(rideHailManager.beamServices.matsimServices.getScenario, repositionTimeout)
    }
  }

  val timeBinToActivities: Map[Int, collection.Set[Activity]] =
    Range(0, activitySegment.maxTime + repositionTimeout, repositionTimeout).zipWithIndex.map {
      case (t, idx) =>
        val activities = activitySegment.getActivities(t, t + repositionTimeout)
        logger.debug(s"Time [$t, ${t + repositionTimeout}], idx $idx, num of activities: ${activities.size}")
        idx -> activities
    }.toMap
  val peakHourNumberOfActivities: Int = timeBinToActivities.maxBy(_._2.size)._2.size

  val timeBinToActivitiesWeight: Map[Int, Double] = timeBinToActivities.map {
    case (timeBin, acts) => timeBin -> acts.size.toDouble / peakHourNumberOfActivities
  }

  logger.info(s"totalNumberOfActivities: ${activitySegment.sorted.length}")
  logger.info(s"peakHourNumberOfActivities: $peakHourNumberOfActivities")
  logger.info(s"sensitivityToDistance: $sensitivityToDistance")
  logger.info(s"sensitivityToDemand: $sensitivityToDemand")
  logger.info(s"predictionHorizonBin: $predictionHorizonBin")

  def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {
    val clusters = createHexClusters(tick)
    logger.debug(s"clusters: ${clusters.length}")
    val nonRepositioningIdleVehicles = idleVehicles.values
    if (nonRepositioningIdleVehicles.nonEmpty) {
      val wantToRepos = ProfilingUtils.timed("Find who wants to reposition", x => logger.debug(x)) {
        nonRepositioningIdleVehicles.filter { rha =>
          shouldReposition(tick, rha)
        }
      }
      val newPositions = ProfilingUtils.timed(s"Find where to repos from ${wantToRepos.size}", x => logger.debug(x)) {
        wantToRepos.flatMap { rha =>
          findWhereToReposition(tick, rha.currentLocationUTM.loc, rha.vehicleId, clusters).map { loc =>
            rha -> loc
          }
        }
      }
      logger.debug(
        s"nonRepositioningIdleVehicles: ${nonRepositioningIdleVehicles.size}, wantToRepos: ${wantToRepos.size}, newPositions: ${newPositions.size}"
      )
      // Filter out vehicles that don't have enough range
      val range = beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters
      newPositions
        .filter { vehAndNewLoc =>
          Skims.od_skimmer
            .getTimeDistanceAndCost(
              vehAndNewLoc._1.currentLocationUTM.loc,
              vehAndNewLoc._2,
              tick,
              CAR,
              vehAndNewLoc._1.vehicleType.id,
              beamServices
            )
            .distance <= rideHailManager.vehicleManager
            .getVehicleState(vehAndNewLoc._1.vehicleId)
            .totalRemainingRange - range
        }
        .map(tup => (tup._1.vehicleId, tup._2))
        .toVector
    } else {
      Vector.empty
    }
  }

  private def shouldReposition(tick: Int, vehicle: RideHailAgentLocation): Boolean = {
    val weights = getTimeBins(tick).map(timeBinToActivitiesWeight.getOrElse(_, 0.0))
    val weight = if (weights.isEmpty) 0.0 else weights.sum / weights.length
    val scaled = weight * sensitivityToDemand
    val rnd = rndGen.nextDouble()
    val shouldRepos = rnd < scaled
    logger.debug(
      s"tick: $tick, currentTimeBin: ${tick / repositionTimeout}, vehicleId: ${vehicle.vehicleId}, rnd: $rnd, weight: $weight, scaled: $scaled, shouldReposition => $shouldRepos"
    )
    shouldRepos
  }

  private def findWhereToReposition(
    tick: Int,
    vehicleLocation: Coord,
    vehicleId: Id[BeamVehicle],
    clusters: Array[ClusterInfo]
  ): Option[Coord] = {
    if (clusters.map(_.size).sum == 0) None
    else {
      val chosenCluster = chooseCluster(vehicleLocation, clusters)
      val chosenCoord = chooseLocation(chosenCluster.activitiesLocation)
      logger.debug(
        s"tick $tick, currentTimeBin: ${tick / repositionTimeout}, vehicleId: $vehicleId, vehicleLocation: $vehicleLocation. sampled: $chosenCluster, drawn coord: $chosenCoord"
      )
      Some(chosenCoord)
    }
  }

  private def chooseCluster(vehicleLocation: Coord, clusters: Array[ClusterInfo]): ClusterInfo = {
    // The probability is proportional to the cluster size per inverse square law -
    // meaning it is proportional to the demand as it appears at a distance from vehicle point of view
    // as higher demands as higher probability
    val pmf = clusters.map { x =>
      val dist = sensitivityToDistance * beamServices.geo.distUTMInMeters(x.coord, vehicleLocation)
      val inverseSquareLaw = 1.0 / Math.max(Math.pow(dist, 2), 1.0)
      new CPair[ClusterInfo, java.lang.Double](x, x.size * inverseSquareLaw)
    }.toVector
    new EnumeratedDistribution[ClusterInfo](rng, pmf.asJava).sample()
  }

  private def chooseLocation(coords: IndexedSeq[Coord]): Coord = {
    // create a probability distribution based on number of activities by sub clusters (sub hexagons)
    val subClusters = coords
      .groupBy(h3taz.getIndex(_, h3taz.getResolution + 1))
      .map {
        case (_, subHex) =>
          new CPair[IndexedSeq[Coord], java.lang.Double](subHex, subHex.size.toDouble)
      }
      .toVector
    val distribution = new EnumeratedDistribution[IndexedSeq[Coord]](rng, subClusters.asJava)
    rndGen.shuffle(distribution.sample()).head
  }

  private def createHexClusters(tick: Int): Array[ClusterInfo] = {
    // Build clusters for every time bin. Number of clusters is configured
    getTimeBins(tick).flatMap(timeBinToActivities.get).flatMap { acts =>
      if (acts.isEmpty)
        Array.empty[ClusterInfo]
      else {
        acts
          .map(_.getCoord)
          .groupBy(beamServices.beamScenario.h3taz.getIndex)
          .map {
            case (hex, group) =>
              val centroid = beamServices.beamScenario.h3taz.getCentroid(hex)
              logger.debug(s"HexIndex: $hex")
              logger.debug(s"Size: ${group.size}")
              logger.debug(s"Center: $centroid")
              ClusterInfo(group.size, centroid, group.toIndexedSeq)
          }
      }
    }
  }

  private def getTimeBins(tick: Int): Array[Int] = {
    import scala.language.postfixOps
    val bin = tick / repositionTimeout
    (bin + 1) to (bin + predictionHorizonBin) toArray
  }
}
