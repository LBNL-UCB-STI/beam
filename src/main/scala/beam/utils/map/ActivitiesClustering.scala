package beam.utils.map

import beam.sim.common.GeoUtils
import beam.utils.csv.readers.BeamCsvScenarioReader
import beam.utils.scenario.PlanElement
import beam.utils.shape.{Attributes, ShapeWriter}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.algorithm.ConvexHull
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Polygon}
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.data.{DoubleVector, NumberVector}
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.matsim.api.core.v01.Coord

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

private case class CoordWithLabel(coord: Coord, label: String)
private case class ClusterInfo(size: Int, coord: Coord, activitiesLocation: IndexedSeq[CoordWithLabel])

private case class ClusterAttributes(size: Int, homes: Int, works: Int) extends Attributes

class ActivitiesClustering(val pathToPlansCsv: String, nClusters: Int) extends StrictLogging {
  private val geometryFactory: GeometryFactory = new GeometryFactory()

  private val geoUtils = new GeoUtils {
    def localCRS: String = "epsg:26910"
  }

  def run(): Unit = {
    val clusters = cluster()
    val clusterWithConvexHull = clusters.map { c =>
      val coords = c.activitiesLocation.map { act =>
        new Coordinate(act.coord.getX, act.coord.getY)
      }
      val convexHullGeom = new ConvexHull(coords.toArray, geometryFactory).getConvexHull
      c -> convexHullGeom
    }
    val shapeWriter = ShapeWriter.worldGeodetic[Polygon, ClusterAttributes]("clusters.shp")
    clusterWithConvexHull.zipWithIndex.foreach { case ((c, geom), idx) =>
      if (geom.getNumPoints > 2) {
        val wgsCoords = geom.getCoordinates.map { c =>
          val wgsCoord = geoUtils.utm2Wgs(new Coord(c.x, c.y))
          new Coordinate(wgsCoord.getX, wgsCoord.getY)
        }
        try {
          val polygon = geometryFactory.createPolygon(wgsCoords)
          val nHomes = c.activitiesLocation.count(x => x.label == "Home")
          val nWorks = c.activitiesLocation.count(x => x.label == "Work")
          shapeWriter.add(
            polygon,
            idx.toString,
            ClusterAttributes(size = c.size, homes = nHomes, works = nWorks)
          )
        } catch {
          case NonFatal(ex) =>
            logger.error("Can't create or add", ex)
        }
      }
    }
    shapeWriter.write()
  }

  private def cluster(): Array[ClusterInfo] = {
    val activities = readActivities(pathToPlansCsv)
    val db = createAndInitializeDatabase(activities)
    val kmeans = new KMeansElkan[NumberVector](
      SquaredEuclideanDistanceFunction.STATIC,
      nClusters,
      1000,
      new RandomUniformGeneratedInitialMeans(RandomFactory.DEFAULT),
      true
    )
    val result = kmeans.run(db)
    result.getAllClusters.asScala.zipWithIndex.map { case (cluster, idx) =>
      logger.info(s"# $idx: ${cluster.getNameAutomatic}")
      logger.info(s"Size: ${cluster.size()}")
      logger.info(s"Model: ${cluster.getModel}")
      logger.info(s"Center: ${cluster.getModel.getMean.toVector}")
      logger.info(s"getPrototype: ${cluster.getModel.getPrototype.toString}")
      val vectors = db.getRelation(TypeUtil.DOUBLE_VECTOR_FIELD)
      val labels = db.getRelation(TypeUtil.STRING)
      val coords: ArrayBuffer[CoordWithLabel] = new ArrayBuffer(cluster.size())
      val iter: DBIDIter = cluster.getIDs.iter()
      while (iter.valid()) {
        val o: DoubleVector = vectors.get(iter)
        val arr = o.toArray
        val coord = new Coord(arr(0), arr(1))
        coords += CoordWithLabel(coord, labels.get(iter))
        iter.advance()
      }
      ClusterInfo(cluster.size, new Coord(cluster.getModel.getMean), coords)
    }.toArray
  }

  private def readActivities(pathToPlansCsv: String): Array[PlanElement] = {
    BeamCsvScenarioReader
      .readPlansFile(pathToPlansCsv)
      .collect { case p if p.planElementType.contains("activity") => p }
  }

  private def createAndInitializeDatabase(acts: scala.collection.Iterable[PlanElement]): Database = {
    val data: Array[Array[Double]] = Array.ofDim[Double](acts.size, 2)
    val labels: Array[String] = Array.ofDim[String](acts.size)
    acts.zipWithIndex.foreach { case (act, idx) =>
      val x = act.activityLocationX.get
      val y = act.activityLocationY.get
      val label = act.activityType.get
      data.update(idx, Array(x, y))
      labels.update(idx, label)
    }
    val dbc = new ArrayAdapterDatabaseConnection(data, labels)
    val db = new StaticArrayDatabase(dbc, null)
    db.initialize()
    db
  }
}

object ActivitiesClustering {

  def main(args: Array[String]): Unit = {
    val ac = new ActivitiesClustering("C:/Users/User/Downloads/plans.csv.gz", 50)
    ac.run()
  }
}
