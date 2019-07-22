package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.utils.{ActivitySegment, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.NumberVector
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.util.Random

case class ClusterInfo(size: Int, coord: Coord)

// To start using it you should set `beam.agentsim.agents.rideHail.repositioningManager.name="DemandFollowingRepositioningManager"` in configuration
// Check `beam-template.conf` to see the configurable parameters
class DemandFollowingRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager)
    with LazyLogging {

  val cfg =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager

  val intervalSize: Int =
    rideHailManager.beamServices.beamConfig.beam.agentsim.agents.rideHail.repositioningManager.timeout

  val activitySegment: ActivitySegment =
    ProfilingUtils.timed(s"Build ActivitySegment with intervalSize $intervalSize", x => logger.info(x)) {
      ActivitySegment(rideHailManager.beamServices.matsimServices.getScenario, intervalSize)
    }

  // When we have all activities, we can make `sensitivityOfRepositioningToDemand` in the range from [0, 1] to make it easer to calibrate
  // If sensitivityOfRepositioningToDemand = 1, it means all vehicles reposition all the time
  // sensitivityOfRepositioningToDemand = 0, means no one reposition
  val sensitivityOfRepositioningToDemand: Double = cfg.sensitivityOfRepositioningToDemand
  val numberOfClustersForDemand = cfg.numberOfClustersForDemand
  val rndGen: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val rng = new MersenneTwister(beamServices.beamConfig.matsim.modules.global.randomSeed) // Random.org

  val hourToAct: Array[(Int, Activity)] = activitySegment.activities.map(act => ((act.getEndTime / 3600).toInt, act))

  val hourToActivities: Array[Array[Activity]] =
    hourToAct.groupBy { case (h, _) => h }.map { case (_, xs) => xs.map(_._2) }.toArray

  // Index is hour, value is number of activities
  val activitiesPerHour: Array[Int] = hourToAct
    .groupBy { case (h, _) => h }
    .map { case (h, xs) => (h, xs.length) }
    .toArray
    .sortBy { case (h, _) => h }
    .map(_._2)

  val totalNumberOfActivities: Int = activitiesPerHour.sum
  val activityWeight: Array[Double] = activitiesPerHour.map(x => x.toDouble / totalNumberOfActivities)
  logger.info(s"totalNumberOfActivities: $totalNumberOfActivities")
  logger.info(s"activitiesPerHour: ${activitiesPerHour.toVector}")
  logger.info(s"activityWeight: ${activityWeight.toVector}")
  logger.info(s"sensitivityOfRepositioningToDemand: $sensitivityOfRepositioningToDemand")
  logger.info(s"numberOfClustersForDemand: $numberOfClustersForDemand")

  val hourToClusters: Array[Array[ClusterInfo]] = ProfilingUtils.timed("createClusters", x => logger.info(x)) {
    createClusters
  }

  def repositionVehicles(tick: Int): Vector[(Id[Vehicle], Location)] = {
    val nonRepositioningIdleVehicles = rideHailManager.vehicleManager.getIdleVehicles.values.filter { ral =>
      rideHailManager.modifyPassengerScheduleManager.isVehicleNeitherRepositioningNorProcessingReservation(
        ral.vehicleId
      )
    }
    if (nonRepositioningIdleVehicles.nonEmpty) {
      val wantToRepos = ProfilingUtils.timed("Find who wants to reposition", x => logger.debug(x)) {
        nonRepositioningIdleVehicles.filter { rha =>
          shouldReposition(tick, rha.vehicleId)
        }
      }
      val newPositions = ProfilingUtils.timed(s"Find where to repos from ${wantToRepos.size}", x => logger.debug(x)) {
        wantToRepos.flatMap { rha =>
          findWhereToReposition(tick, rha.currentLocationUTM.loc, rha.vehicleId).map { loc =>
            rha.vehicleId -> loc
          }
        }
      }
      logger.info(
        s"nonRepositioningIdleVehicles: ${nonRepositioningIdleVehicles.size}, wantToRepos: ${wantToRepos.size}, newPositions: ${newPositions.size}"
      )
      newPositions.toVector
    } else {
      Vector.empty
    }
  }

  private def shouldReposition(tick: Int, vehicleId: Id[Vehicle]): Boolean = {
    val currentHour = tick / 3600
    val weight = activityWeight.lift(currentHour).getOrElse(0.0)
    val scaled = weight * sensitivityOfRepositioningToDemand
    val rnd = rndGen.nextDouble()
    val shouldRepos = rnd < scaled
    logger.debug(
      s"tick: $tick, hour: $currentHour, vehicleId: $vehicleId, rnd: $rnd, weight: $weight, scaled: $scaled, shouldReposition => $shouldRepos"
    )
    shouldRepos
  }

  private def findWhereToReposition(tick: Int, vehicleLocation: Coord, vehicleId: Id[Vehicle]): Option[Coord] = {
    val currentHour = tick / 3600
    val nextHour = currentHour + 1
    hourToClusters.lift(nextHour).map { clusters =>
      // We get top 5 closest clusters and randomly pick one of them.
      // The probability is proportional to the cluster size - meaning it is proportional to the demand, as higher demands as higher probability
      val top5Closest = clusters.sortBy(x => beamServices.geo.distUTMInMeters(x.coord, vehicleLocation)).take(5)
      val pmf = top5Closest.map { x =>
        new CPair[ClusterInfo, java.lang.Double](x, x.size.toDouble)
      }.toList
      val distr = new EnumeratedDistribution[ClusterInfo](rng, pmf.asJava)
      val sampled = distr.sample()
      logger.debug(
        s"tick $tick, currentHour: $currentHour, nextHour: $nextHour, vehicleId: $vehicleId, vehicleLocation: $vehicleLocation. Top 5 closest: ${top5Closest.toVector}, sampled: $sampled"
      )
      sampled.coord
    }
  }

  private def createClusters: Array[Array[ClusterInfo]] = {
    // Build clusters for every hour. Number of clusters is configured
    hourToActivities.zipWithIndex.map {
      case (acts, hour) =>
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
          logger.debug(s"Hour $hour")
          result.getAllClusters.asScala.zipWithIndex.map {
            case (clu, idx) =>
              logger.debug(s"# $idx: ${clu.getNameAutomatic}")
              logger.debug(s"Size: ${clu.size()}")
              logger.debug(s"Model: ${clu.getModel}")
              logger.debug(s"Center: ${clu.getModel.getMean.toVector}")
              logger.debug(s"getPrototype: ${clu.getModel.getPrototype.toString}")
              ClusterInfo(clu.size, new Coord(clu.getModel.getMean))
          }.toArray
        } catch {
          case ex: Exception =>
            logger.error("err clustering", ex)
            throw ex
        }
    }
  }

  private def createDatabase(acts: Array[Activity]): Database = {
    val data = Array.ofDim[Double](acts.length, 2)
    acts.zipWithIndex.foreach {
      case (act, idx) =>
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
}
