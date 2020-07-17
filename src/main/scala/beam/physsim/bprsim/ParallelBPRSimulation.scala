package beam.physsim.bprsim

import beam.physsim.bprsim.ParallelBPRSimulation.createClusters
import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.LazyLogging
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.NumberVector
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.relation.Relation
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/**
  *
  * @author Dmitry Openkov
  */
class ParallelBPRSimulation(scenario: Scenario, config: BPRSimConfig, eventManager: EventsManager, seed: Long)
    extends Mobsim
    with LazyLogging {

  override def run(): Unit = {
    if (scenario.getNetwork.getLinks.size() > 0) {
      val clusters = createClusters(scenario.getNetwork.getLinks.values().asScala, config.numberOfClusters, seed: Long)
      val coordinator: Coordinator = new Coordinator(clusters, scenario, config, eventManager)
      coordinator.start()
      config.caccSettings.foreach(_.roadCapacityAdjustmentFunction.printStats())
    } else {
      ParallelBPRSimulation.logger.info("No links in the networks")
    }
  }
}

object ParallelBPRSimulation extends LazyLogging {
  private def createClusters(links: Iterable[Link], numClusters: Int, seed: Long): Vector[Set[Id[Link]]] = {
    logger.info(s"creating clusters, links.size = ${links.size}")
    if (links.isEmpty) {
      Vector.empty
    } else {
      val db: Database = createDatabase(links)
      try {
        val kmeans = new KMeansElkan[NumberVector](
          SquaredEuclideanDistanceFunction.STATIC,
          numClusters,
          2000,
          new RandomUniformGeneratedInitialMeans(RandomFactory.get(seed)),
          true
        )
        val result = kmeans.run(db)
        val clusters = result.getAllClusters.asScala.map { clu =>
          val labels: Relation[String] = db.getRelation(TypeUtil.STRING)
          val clusterLinkIds: ArrayBuffer[Id[Link]] = new ArrayBuffer(clu.size())
          val iter: DBIDIter = clu.getIDs.iter()
          while (iter.valid()) {
            val idStr: String = labels.get(iter)
            clusterLinkIds += Id.createLinkId(idStr)
            iter.advance()
          }
          clusterLinkIds.toSet
        }
        logger.info(s"Done clustering: ${clusters.size}")
        logger.info(s"Link distribution: ${clusters.map(_.size).mkString(", ")}")
        clusters.toVector
      } catch {
        case ex: Exception =>
          logger.error("error clustering", ex)
          throw ex
      }
    }
  }

  private def createDatabase(links: Iterable[Link]): Database = {
    val data = Array.ofDim[Double](links.size, 2)
    val labels: Array[String] = Array.ofDim[String](links.size)
    links.zipWithIndex.foreach {
      case (link, idx) =>
        val center = GeoUtils.linkCenter(link)
        data.update(idx, Array(center.getX, center.getY))
        labels.update(idx, idx.toString)
    }
    val dbc = new ArrayAdapterDatabaseConnection(data, labels)
    val db = new StaticArrayDatabase(dbc, null)
    db.initialize()
    db
  }
}
