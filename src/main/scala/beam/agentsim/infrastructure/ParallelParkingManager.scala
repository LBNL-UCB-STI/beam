package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.event.Logging
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.ParallelParkingManager.{ParkingCluster, Worker}
import beam.agentsim.infrastructure.parking.ParkingZone
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.common.GeoUtils.toJtsCoordinate
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.algorithm.ConvexHull
import com.vividsolutions.jts.geom.prep.{PreparedGeometry, PreparedGeometryFactory}
import com.vividsolutions.jts.geom.{Coordinate, Envelope, GeometryFactory}
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.data.{DoubleVector, NumberVector}
import de.lmu.ifi.dbs.elki.database.StaticArrayDatabase
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.relation.Relation
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Random

/**
  *
  * @author Dmitry Openkov
  */
class ParallelParkingManager(
  beamConfig: BeamConfig,
  tazTreeMap: TAZTreeMap,
  clusters: Vector[ParkingCluster],
  zones: Array[ParkingZone],
  searchTree: ZoneSearchTree[TAZ],
  geo: GeoUtils,
  seed: Int,
  boundingBox: Envelope
) extends beam.utils.CriticalActor
    with ActorLogging {
  private val counter = {
    val displayPerformanceTimings = beamConfig.beam.agentsim.taz.parkingManager.displayPerformanceTimings
    val logLevel = if (displayPerformanceTimings) Logging.InfoLevel else Logging.DebugLevel
    new SimpleCounter(log, logLevel, "Receiving {} per seconds of ParkingInquiry for {}")
  }
  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)

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
              new Random(seed),
              boundingBox
            )
            .withDispatcher("parallel-parking-manager-dispatcher"),
          s"zonal-parking-manager-$i"
        )
        Worker(actor, cluster, emergencyId)
    }

  private val tazToWorker: Map[Id[TAZ], Worker] = mapTazToWorker(workers)

  override def receive: Receive = {
    case inquiry: ParkingInquiry =>
      counter.count("all")
      val foundCluster = workers.find { w =>
        val point = ParallelParkingManager.geometryFactory.createPoint(inquiry.destinationUtm)
        w.cluster.convexHull.contains(point)
      }

      val worker = foundCluster
        .orElse(
          tazToWorker.get(findTazId(inquiry))
        )
        .get

      counter.count(worker.cluster.presentation)
      worker.actor.forward(inquiry)

    case release @ ReleaseParkingStall(parkingZoneId, tazId) =>
      tazToWorker.get(tazId) match {
        case Some(worker) => worker.actor.forward(release)
        case None         => log.error(s"No TAZ with id $tazId, zone id = $parkingZoneId. Cannot release.")
      }

    case "tick" =>
      counter.tick()
  }

  private def findTazId(inquiry: ParkingInquiry): Id[TAZ] = {
    if (tazTreeMap.tazQuadTree.size() == 0)
      workers.head.emergencyId
    else
      tazTreeMap.getTAZ(inquiry.destinationUtm.getX, inquiry.destinationUtm.getY).tazId
  }

  private def mapTazToWorker(clusters: Seq[Worker]): Map[Id[TAZ], Worker] = {
    clusters.flatMap { worker =>
      (worker.cluster.tazes.view.map(_.tazId) :+ worker.emergencyId).map(_ -> worker)
    }.toMap
  }

  override def postStop(): Unit = {
    tickTask.cancel()
  }
}

object ParallelParkingManager extends LazyLogging {
  private val geometryFactory = new GeometryFactory()

  case class ParkingSearchResult(response: ParkingInquiryResponse, originalSender: ActorRef, worker: ActorRef)

  /**
    * builds a ParallelParkingManager Actor
    *
    * @return
    */
  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    boundingBox: Envelope
  ): Props = {
    val numClusters =
      Math.min(tazTreeMap.tazQuadTree.size(), beamConfig.beam.agentsim.taz.parkingManager.parallel.numberOfClusters)
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val filePath: String = beamConfig.beam.agentsim.taz.filePath
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor
    val random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val seed = beamConfig.matsim.modules.global.randomSeed
    val (zones, searchTree) = ZonalParkingManager.loadParkingZones(
      parkingFilePath,
      filePath,
      parkingStallCountScalingFactor,
      parkingCostScalingFactor,
      random
    )
    props(beamConfig, tazTreeMap, zones, searchTree, numClusters, geo, seed, boundingBox)
  }

  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    zones: Array[ParkingZone],
    searchTree: ZoneSearchTree[TAZ],
    numClusters: Int,
    geo: GeoUtils,
    seed: Int,
    boundingBox: Envelope
  ): Props = {
    val clusters: Vector[ParkingCluster] = createClusters(tazTreeMap, zones, numClusters, seed.toLong)

    Props(
      new ParallelParkingManager(
        beamConfig,
        tazTreeMap,
        clusters,
        zones,
        searchTree,
        geo,
        seed,
        boundingBox
      )
    )
  }

  private[infrastructure] case class ParkingCluster(
    tazes: Vector[TAZ],
    mean: Coord,
    convexHull: PreparedGeometry,
    presentation: String
  )

  private case class Worker(actor: ActorRef, cluster: ParkingCluster, emergencyId: Id[TAZ])

  private[infrastructure] def createClusters(
    tazTreeMap: TAZTreeMap,
    zones: Array[ParkingZone],
    numClusters: Int,
    seed: Long
  ): Vector[ParkingCluster] = {
    logger.info(s"creating clusters, tazTreeMap.size = ${tazTreeMap.tazQuadTree.size} zones.size = ${zones.length}")
    val pgf = new PreparedGeometryFactory
    if (tazTreeMap.tazQuadTree.size() == 0) {
      val polygonCoords = Array(
        new Coordinate(0, 0),
        new Coordinate(1, 0),
        new Coordinate(1, 1),
        new Coordinate(0, 1),
        new Coordinate(0, 0),
      )
      val polygon = geometryFactory.createPolygon(polygonCoords)
      Vector(
        ParkingCluster(
          tazTreeMap.getTAZs.toVector,
          new Coord(0.0, 0.0),
          pgf.create(polygon),
          "single-empty-cluster"
        )
      )
    } else {
      val (emptyTAZes, db) = createDatabase(tazTreeMap, zones)
      val kmeans = new KMeansElkan[NumberVector](
        SquaredEuclideanDistanceFunction.STATIC,
        numClusters,
        2000,
        new RandomUniformGeneratedInitialMeans(RandomFactory.get(seed)),
        true
      )
      val result = kmeans.run(db)
      val clusters = result.getAllClusters.asScala.toVector.zipWithIndex.map {
        case (clu, idx) =>
          val rel = db.getRelation(TypeUtil.DOUBLE_VECTOR_FIELD)
          val labels: Relation[String] = db.getRelation(TypeUtil.STRING)
          val coords: ArrayBuffer[Coordinate] = new ArrayBuffer(clu.size())
          val clusterZones: ArrayBuffer[ParkingZone] = new ArrayBuffer(clu.size())
          val empty: ArrayBuffer[TAZ] = new ArrayBuffer[TAZ]()
          val iter: DBIDIter = clu.getIDs.iter()
          while (iter.valid()) {
            val o: DoubleVector = rel.get(iter)
            val id: String = labels.get(iter)
            if (id.startsWith("taz")) {
              empty += emptyTAZes(id.substring(3).toInt)
            } else {
              clusterZones += zones(id.toInt)
            }
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
          val ch = new ConvexHull(dCoords.toArray, geometryFactory).getConvexHull
          val convexHull = pgf.create(ch)
          val tazes = clusterZones
            .map(_.tazId)
            .distinct
            .map(tazTreeMap.getTAZ(_).get) ++ empty
          val centroid = ch.getCentroid
          val clusterMeanStr = String.format("(%.2f, %.2f)", Double.box(centroid.getX), Double.box(centroid.getY))
          ParkingCluster(tazes.toVector, new Coord(clu.getModel.getMean), convexHull, s"$idx-$clusterMeanStr")
      }
      logger.info(s"Done clustering: ${clusters.size}")
      logger.info(s"TAZ distribution: ${clusters.map(_.tazes.size).mkString(", ")}")
      clusters
    }
  }

  private def createDatabase(tazTreeMap: TAZTreeMap, zones: Array[ParkingZone]): (Array[TAZ], StaticArrayDatabase) = {
    case class ZoneInfo(coord: Coord, label: String)
    val zoneInfos = {
      zones.zipWithIndex.flatMap {
        case (zone, idx) =>
          tazTreeMap
            .getTAZ(zone.tazId)
            .map(taz => ZoneInfo(taz.coord, idx.toString))
      }
    }
    val emptyTAZes = (tazTreeMap.getTAZs.toSet -- zones.flatMap(zone => tazTreeMap.getTAZ(zone.tazId)).toSet).toArray
    val virtualZones = emptyTAZes.zipWithIndex.map {
      case (taz, idx) => ZoneInfo(taz.coord, s"taz$idx")
    }
    val allZones = zoneInfos ++ virtualZones

    val data = allZones.map(zi => Array(zi.coord.getX, zi.coord.getY))
    val labels: Array[String] = allZones.map(_.label)
    val dbc = new ArrayAdapterDatabaseConnection(data, labels)
    val db = new StaticArrayDatabase(dbc, null)
    db.initialize()
    (emptyTAZes, db)
  }

}
