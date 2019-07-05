package beam.agentsim.agents.ridehail.allocation

import beam.sim.BeamServices
import beam.utils.ActivitySegment
import com.typesafe.scalalogging.LazyLogging

import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.util.Random

import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.NumberVector
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory

case class ClusterInfo(size: Int, coord: Coord)

class Algo8Repos(val beamServices: BeamServices, val activitySegment: ActivitySegment) extends LazyLogging {

  val sensitivityOfRepositioningToDemand: Double =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.sensitivityOfRepositioningToDemand

  val numberOfClustersForDemand =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.numberOfClustersForDemand
  val rndGen: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val rng = new MersenneTwister(beamServices.beamConfig.matsim.modules.global.randomSeed) // Random.org

  val hourToAct: Array[(Int, Activity)] = activitySegment.activities.map(act => ((act.getEndTime / 3600).toInt, act))
  val hourToActivities = hourToAct.groupBy { case (h, _) => h }.map { case (_, xs) => xs.map(_._2) }.toArray

  val activitiesPerHour: Array[Int] = hourToAct.groupBy { case (h, _) => h }
    .map { case (h, xs) => (h, xs.length) }
    .toArray.sortBy { case (h, _) => h }.map(_._2)

  val totalNumberOfActivities: Int = activitiesPerHour.sum
  val activityWeight: Array[Double] = activitiesPerHour.map(x => x.toDouble / totalNumberOfActivities)
  logger.info(s"totalNumberOfActivities: ${totalNumberOfActivities}")
  logger.info(s"activitiesPerHour: ${activitiesPerHour.toVector}")
  logger.info(s"activityWeight: ${activityWeight.toVector}")
  logger.info(s"sensitivityOfRepositioningToDemand: ${sensitivityOfRepositioningToDemand}")
  logger.info(s"numberOfClustersForDemand: ${numberOfClustersForDemand}")

  val hourToClusters: Array[Array[ClusterInfo]] = clustering()

  def clustering(): Array[Array[ClusterInfo]] = {
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

  def shouldReposition(tick: Int, vehicleId: Id[Vehicle]): Boolean = {
    val currentHour = tick / 3600
    val weight = activityWeight.lift(currentHour).getOrElse(0.0)
    val scaled = weight * sensitivityOfRepositioningToDemand
    val rnd = rndGen.nextDouble()
    val shouldRepos = rnd < scaled
    logger.debug(
      s"tick: ${tick}, hour: ${currentHour}, vehicleId: $vehicleId, rnd: ${rnd}, weight: ${weight}, scaled: ${scaled}, shouldReposition => ${shouldRepos}"
    )
    shouldRepos
  }

  def findWhereToReposition(tick: Int, vehicleLocation: Coord, vehicleId: Id[Vehicle]): Option[Coord] = {
    val currentHour = tick / 3600
    val nextHour = currentHour + 1
    hourToClusters.lift(nextHour).map { clusters =>
      val top5Closest = clusters.sortBy(x => beamServices.geo.distUTMInMeters(x.coord, vehicleLocation)).take(5)
      val pmf = top5Closest.map { x =>
        new CPair[ClusterInfo, java.lang.Double](x, x.size.toDouble)
      }.toList
      val distr = new EnumeratedDistribution[ClusterInfo](rng, pmf.asJava)
      val sampled = distr.sample()
      logger.debug(
        s"tick ${tick}, currentHour: ${currentHour}, nextHour: ${nextHour}, vehicleId: ${vehicleId}, vehicleLocation: ${vehicleLocation}. Top 5 closest: ${top5Closest.toVector}, sampled: ${sampled}"
      )
      sampled.coord
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
