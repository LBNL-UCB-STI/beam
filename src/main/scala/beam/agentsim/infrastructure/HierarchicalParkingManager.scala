package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.HierarchicalParkingManager.{ParkingCluster, Worker}
import beam.agentsim.infrastructure.parking.ParkingZone
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.common.GeoUtils.toJtsCoordinate
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.algorithm.ConvexHull
import com.vividsolutions.jts.geom.{Coordinate, Envelope, Geometry, GeometryFactory}
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.data.{DoubleVector, NumberVector}
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.relation.Relation
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.util.Random

/**
  *
  * @author Dmitry Openkov
  */
class HierarchicalParkingManager(
  beamConfig: BeamConfig,
  tazTreeMap: TAZTreeMap,
  clusters: Vector[ParkingCluster],
  zones: Array[ParkingZone],
  searchTree: ZoneSearchTree[TAZ],
  geo: GeoUtils,
  rand: Random,
  boundingBox: Envelope
) extends beam.utils.CriticalActor
    with ActorLogging {
  private implicit val timeout: Timeout = Timeout(100, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private val workers: Vector[Worker] =
    clusters.zipWithIndex.map {
      case (cluster, i) =>
        val emergencyId = Id.create(s"emergency-$i", classOf[TAZ])
        val actor = context.actorOf(
          ZonalParkingManager
            .props(
              beamConfig,
              TAZTreeMap.fromSeq(cluster.tazes),
              zones,
              searchTree,
              emergencyId,
              geo,
              rand,
              boundingBox
            ),
          s"zonal-parking-manager-$i"
        )
        Worker(actor, cluster, emergencyId)
    }

  private val tazToWorker: Map[Id[TAZ], Worker] = mapTazToWorker(workers)

  override def receive = {
    case inquiry: ParkingInquiry =>
      val foundCluster = workers.find { w =>
        val point = HierarchicalParkingManager.geometryFactory.createPoint(inquiry.destinationUtm)
        w.cluster.convexHull.contains(point)
      }

      val worker =
        foundCluster.getOrElse(
          tazToWorker.getOrElse(
            tazTreeMap.getTAZ(inquiry.destinationUtm.getX, inquiry.destinationUtm.getY).tazId,
            throw new IllegalStateException(s"No TAZ around for $inquiry")
          )
        )

      worker.actor.forward(inquiry)

    case release @ ReleaseParkingStall(parkingZoneId, tazId) =>
      val worker = tazToWorker.getOrElse(
          tazId,
          throw new IllegalStateException(s"No TAZ with id $tazId, zone id = $parkingZoneId")
        )

      worker.actor.forward(release)
  }

  private def mapTazToWorker(clusters: Seq[Worker]): Map[Id[TAZ], Worker] = {
    clusters.flatMap { worker =>
      (worker.cluster.tazes.view.map(_.tazId) :+ worker.emergencyId).map(_ -> worker)
    }.toMap
  }
}

object HierarchicalParkingManager extends LazyLogging {
  private val geometryFactory = new GeometryFactory()

  case class ParkingSearchResult(response: ParkingInquiryResponse, originalSender: ActorRef, worker: ActorRef)

  /**
    * builds a HierarchicalParkingManager Actor
    *
    * @return
    */
  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    boundingBox: Envelope
  ): Props = {
    val numClusters = Math.min(tazTreeMap.tazQuadTree.size(),
      beamConfig.beam.agentsim.taz.parkingManager.hierarchical.numberOfClusters)
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val filePath: String = beamConfig.beam.agentsim.taz.filePath
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor
    val random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val (zones, searchTree) = ZonalParkingManager.loadParkingZones(
      parkingFilePath,
      filePath,
      parkingStallCountScalingFactor,
      parkingCostScalingFactor,
      random
    )
    props(beamConfig, tazTreeMap, zones, searchTree, numClusters, geo, random, boundingBox)
  }

  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    zones: Array[ParkingZone],
    searchTree: ZoneSearchTree[TAZ],
    numClusters: Int,
    geo: GeoUtils,
    rand: Random,
    boundingBox: Envelope
  ) = {
    val clusters: Vector[ParkingCluster] = createClusters(tazTreeMap, zones, numClusters)

    Props(
      new HierarchicalParkingManager(
        beamConfig,
        tazTreeMap,
        clusters,
        zones,
        searchTree,
        geo,
        rand,
        boundingBox
      )
    )
  }

  private[infrastructure] case class ParkingCluster(tazes: Vector[TAZ], mean: Coord, convexHull: Geometry)
  private case class Worker(actor: ActorRef, cluster: ParkingCluster, emergencyId: Id[TAZ])

  private[infrastructure] def createClusters(
    tazTreeMap: TAZTreeMap,
    zones: Array[ParkingZone],
    numClusters: Int
  ): Vector[ParkingCluster] = {
    logger.info(s"creating clusters, tazTreeMap.size = ${tazTreeMap.tazQuadTree.size} zones.size = ${zones.length}")
    if (zones.isEmpty) {
      Vector(
        ParkingCluster(
          tazTreeMap.getTAZs.toVector,
          new Coord(0.0, 0.0),
          new ConvexHull(Array.empty, geometryFactory).getConvexHull
        )
      )
    } else {
      val db: Database = createDatabase(tazTreeMap, zones)
      try {
        val kmeans = new KMeansElkan[NumberVector](
          SquaredEuclideanDistanceFunction.STATIC,
          numClusters,
          2000,
          new RandomUniformGeneratedInitialMeans(RandomFactory.DEFAULT),
          true
        )
        val result = kmeans.run(db)
        val clusters = result.getAllClusters.asScala.toVector.map { clu =>
          val rel = db.getRelation(TypeUtil.DOUBLE_VECTOR_FIELD)
          val labels: Relation[String] = db.getRelation(TypeUtil.STRING)
          val coords: ArrayBuffer[Coordinate] = new ArrayBuffer(clu.size())
          val clusterZones: ArrayBuffer[ParkingZone] = new ArrayBuffer(clu.size())
          val iter: DBIDIter = clu.getIDs.iter()
          while (iter.valid()) {
            val o: DoubleVector = rel.get(iter)
            val id: String = labels.get(iter)
            clusterZones += zones(id.toInt)
            coords += new Coordinate(o.doubleValue(0), o.doubleValue(1))
            iter.advance()
          }
          val dCoords = coords.distinct
          if (dCoords.size == 1) {
            //means a single point which is not allowed by ConvexHull
            val center = coords(0)
            dCoords(0) = new Coordinate(center.x - .1, center.y - .1)
            dCoords += new Coordinate(center.x + .1, center.y - .1)
            dCoords += new Coordinate(center.x + .1, center.y + .1)
            dCoords += new Coordinate(center.x - .1, center.y + .1)
          }
          val convexHull = new ConvexHull(dCoords.toArray, geometryFactory).getConvexHull
          val tazes = clusterZones
            .map(_.tazId)
            .distinct
            .map(tazTreeMap.getTAZ(_).get)
            .toList
          ParkingCluster(tazes.toVector, new Coord(clu.getModel.getMean), convexHull)
        }
        logger.info(s"Done clustering: ${clusters.size}")
        logger.info(s"TAZ distribution: ${clusters.map(_.tazes.size).mkString(", ")}")
        clusters
      } catch {
        case ex: Exception =>
          logger.error("error clustering", ex)
          throw ex
      }
    }
  }

  private def createDatabase(tazTreeMap: TAZTreeMap, zones: Array[ParkingZone]): Database = {
    val data = Array.ofDim[Double](zones.length, 2)
    val labels: Array[String] = Array.ofDim[String](zones.length)
    zones.zipWithIndex.foreach {
      case (zone, idx) =>
        val taz = tazTreeMap.getTAZ(zone.tazId)
        if (taz.isEmpty) logger.warn(s"No TAZ with id ${zone.tazId} found")
        for {
          t <- taz
          x = t.coord.getX
          y = t.coord.getY
        } yield {
          data.update(idx, Array(x, y))
          labels.update(idx, idx.toString)
        }
    }
    val dbc = new ArrayAdapterDatabaseConnection(data, labels)
    // Create a database (which may contain multiple relations!)
    val db = new StaticArrayDatabase(dbc, null)
    // Load the data into the database (do NOT forget to initialize...)
    db.initialize()
    db
  }

}
