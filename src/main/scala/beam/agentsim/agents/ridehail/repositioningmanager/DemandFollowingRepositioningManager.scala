package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.BeamRouter
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import beam.utils.{ActivitySegment, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.data.{DoubleVector, NumberVector}
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

// To start using it you should set `beam.agentsim.agents.rideHail.repositioningManager.name="DEMAND_FOLLOWING_REPOSITIONING_MANAGER"` in configuration
// Check `beam-template.conf` to see the configurable parameters
class DemandFollowingRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager)
    with LazyLogging {

  private val activitySegment: ActivitySegment = {
    ProfilingUtils.timed(s"Build ActivitySegment with bin size $repositionTimeout", x => logger.info(x)) {
      ActivitySegment(rideHailManager.beamServices.matsimServices.getScenario, repositionTimeout)
    }
  }

  // When we have all activities, we can make `sensitivityOfRepositioningToDemand` in the range from [0, 1] to make it easer to calibrate
  // If sensitivityOfRepositioningToDemand = 1, it means all vehicles reposition all the time
  // sensitivityOfRepositioningToDemand = 0, means no one reposition
  private val cfg =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager

  val sensitivityOfRepositioningToDemand: Double = cfg.sensitivityOfRepositioningToDemand
  val sensitivityOfRepositioningToDemandForCAVs: Double = cfg.sensitivityOfRepositioningToDemandForCAVs
  val numberOfClustersForDemand: Int = cfg.numberOfClustersForDemand
  val rndGen: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val rng: MersenneTwister = new MersenneTwister(beamServices.beamConfig.matsim.modules.global.randomSeed) // Random.org

  val horizon: Int = if (cfg.horizon < repositionTimeout) {
    logger.warn(
      s"horizon[${cfg.horizon} is less than repositioningManager.timeout[$repositionTimeout], will use repositioningManager.timeout"
    )
    repositionTimeout
  } else {
    cfg.horizon
  }

  val timeBinToActivities: Map[Int, collection.Set[Activity]] =
    Range(0, activitySegment.maxTime + horizon, horizon).zipWithIndex.map { case (t, idx) =>
      val activities = activitySegment.getActivities(t, t + horizon)
      logger.debug(s"Time [$t, ${t + horizon}], idx $idx, num of activities: ${activities.size}")
      idx -> activities
    }.toMap

  val totalNumberOfActivities: Int = activitySegment.sorted.length

  val timeBinToActivitiesWeight: Map[Int, Double] = timeBinToActivities.map { case (timeBin, acts) =>
    timeBin -> acts.size.toDouble / totalNumberOfActivities
  }
  logger.info(s"totalNumberOfActivities: $totalNumberOfActivities")

  val sortedTimeBinToActivitiesWeight: Vector[(Int, Double)] = timeBinToActivitiesWeight.toVector.sortBy {
    case (timeBin, _) => timeBin
  }
  logger.debug(s"timeBinToActivitiesWeight: $sortedTimeBinToActivitiesWeight")
  logger.info(s"sensitivityOfRepositioningToDemand: $sensitivityOfRepositioningToDemand")
  logger.info(s"numberOfClustersForDemand: $numberOfClustersForDemand")
  logger.info(s"horizon: $horizon")

  val timeBinToClusters: Map[Int, Array[ClusterInfo]] = ProfilingUtils.timed("createClusters", x => logger.info(x)) {
    createClusters
  }

  def repositionVehicles(
    idleVehicles: scala.collection.Map[Id[BeamVehicle], RideHailAgentLocation],
    tick: Int
  ): Vector[(Id[BeamVehicle], Location)] = {
    val nonRepositioningIdleVehicles = idleVehicles.values
    if (nonRepositioningIdleVehicles.nonEmpty) {
      val wantToRepos = ProfilingUtils.timed("Find who wants to reposition", x => logger.debug(x)) {
        nonRepositioningIdleVehicles.filter { rha =>
          shouldReposition(tick, rha)
        }
      }
      val newPositions = ProfilingUtils.timed(s"Find where to repos from ${wantToRepos.size}", x => logger.debug(x)) {
        wantToRepos.flatMap { rha =>
          findWhereToReposition(tick, rha.getCurrentLocationUTM(tick, beamServices), rha.vehicleId).map { loc =>
            rha -> loc
          }
        }
      }
      logger.debug(
        s"nonRepositioningIdleVehicles: ${nonRepositioningIdleVehicles.size}, wantToRepos: ${wantToRepos.size}, newPositions: ${newPositions.size}"
      )

      // Filter out vehicles that don't have enough range
      newPositions
        .filter { vehAndNewLoc =>
          BeamRouter
            .computeTravelTimeAndDistanceAndCost(
              vehAndNewLoc._1.getCurrentLocationUTM(tick, beamServices),
              vehAndNewLoc._2,
              tick,
              CAR,
              vehAndNewLoc._1.vehicleType.id,
              vehAndNewLoc._1.vehicleType,
              beamServices.beamScenario.fuelTypePrices(vehAndNewLoc._1.vehicleType.primaryFuelType),
              beamServices.beamScenario,
              beamServices.skims.od_skimmer
            )
            .distance <= rideHailManager
            .resources(vehAndNewLoc._1.vehicleId)
            .getTotalRemainingRange - rideHailManager.beamScenario.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters
        }
        .map(tup => (tup._1.vehicleId, tup._2))
        .toVector
    } else {
      Vector.empty
    }
  }

  private def shouldReposition(tick: Int, vehicle: RideHailAgentLocation): Boolean = {
    val currentTimeBin = getTimeBin(tick)
    val weight = timeBinToActivitiesWeight.getOrElse(currentTimeBin, 0.0)
    val scaled = weight * (if (vehicle.vehicleType.automationLevel >= 4) {
                             sensitivityOfRepositioningToDemandForCAVs
                           } else {
                             sensitivityOfRepositioningToDemand
                           })
    val rnd = rndGen.nextDouble()
    val shouldRepos = rnd < scaled
    logger.debug(
      s"tick: $tick, currentTimeBin: $currentTimeBin, vehicleId: ${vehicle.vehicleId}, rnd: $rnd, weight: $weight, scaled: $scaled, shouldReposition => $shouldRepos"
    )
    shouldRepos
  }

  private def findWhereToReposition(tick: Int, vehicleLocation: Coord, vehicleId: Id[BeamVehicle]): Option[Coord] = {
    val currentTimeBin = getTimeBin(tick)
    val nextTimeBin = currentTimeBin + 1
    val fractionOfClosestClusters =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.fractionOfClosestClustersToConsider

    timeBinToClusters.get(nextTimeBin).flatMap { clusters =>
      if (clusters.map(_.size).sum == 0) None
      else {
        val N: Int = Math.max(1, Math.round(clusters.length * fractionOfClosestClusters).toInt)

        // We get top N closest clusters and randomly pick one of them.
        // The probability is proportional to the cluster size - meaning it is proportional to the demand, as higher demands as higher probability
        val topNClosest = clusters.sortBy(x => beamServices.geo.distUTMInMeters(x.coord, vehicleLocation)).take(N)
        val pmf = topNClosest.map { x =>
          new CPair[ClusterInfo, java.lang.Double](x, x.size.toDouble)
        }.toVector

        val distr = new EnumeratedDistribution[ClusterInfo](rng, pmf.asJava)
        val sampled = distr.sample()
        // Randomly pick the coordinate of one of activities
        val drawnCoord = rndGen.shuffle(sampled.activitiesLocation).head
        logger.debug(
          s"tick $tick, currentTimeBin: $currentTimeBin, nextTimeBin: $nextTimeBin, vehicleId: $vehicleId, vehicleLocation: $vehicleLocation. Top $N closest: ${topNClosest.toVector}, sampled: $sampled, drawn coord: $drawnCoord"
        )
        Some(drawnCoord)
      }
    }
  }

  private def createClusters: Map[Int, Array[ClusterInfo]] = {
    // Build clusters for every time bin. Number of clusters is configured
    timeBinToActivities.map { case (timeBin, acts) =>
      val clusters =
        if (acts.isEmpty) Array.empty[ClusterInfo]
        else {
          val db: Database = createDatabase(acts)
          try {
            val kmeans = new KMeansElkan[NumberVector](
              SquaredEuclideanDistanceFunction.STATIC,
              numberOfClustersForDemand,
              1000,
              new RandomUniformGeneratedInitialMeans(RandomFactory.DEFAULT),
              true
            )
            val result = kmeans.run(db)
            logger.debug(s"timeBin: $timeBin, seconds: ${timeBinToSeconds(timeBin)}")
            result.getAllClusters.asScala.zipWithIndex.map { case (clu, idx) =>
              logger.debug(s"# $idx: ${clu.getNameAutomatic}")
              logger.debug(s"Size: ${clu.size()}")
              logger.debug(s"Model: ${clu.getModel}")
              logger.debug(s"Center: ${clu.getModel.getMean.toVector}")
              logger.debug(s"getPrototype: ${clu.getModel.getPrototype.toString}")
              val rel = db.getRelation(TypeUtil.DOUBLE_VECTOR_FIELD)
              val coords: ArrayBuffer[Coord] = new ArrayBuffer(clu.size())
              val iter: DBIDIter = clu.getIDs.iter()
              while (iter.valid()) {
                val o: DoubleVector = rel.get(iter)
                val arr = o.toArray
                coords += new Coord(arr(0), arr(1))
                iter.advance()
              }
              ClusterInfo(clu.size, new Coord(clu.getModel.getMean), coords)
            }.toArray
          } catch {
            case ex: Exception =>
              logger.error("err clustering", ex)
              throw ex
          }
        }
      timeBin -> clusters
    }
  }

  private def createDatabase(acts: scala.collection.Iterable[Activity]): Database = {
    val data = Array.ofDim[Double](acts.size, 2)
    acts.zipWithIndex.foreach { case (act, idx) =>
      val x = act.getCoord.getX
      val y = act.getCoord.getY
      data.update(idx, Array(x, y))
    }
    val dbc = new ArrayAdapterDatabaseConnection(data)
    // Create a database (which may contain multiple relations!)
    val db = new StaticArrayDatabase(dbc, null)
    // Load the data into the database (do NOT forget to initialize...)
    db.initialize()
    db
  }

  private def getTimeBin(tick: Int): Int = tick / horizon

  private def timeBinToSeconds(timeBin: Int): Int = timeBin * horizon
}
