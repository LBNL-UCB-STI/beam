package beam.physsim.jdeqsim

import java.util
import java.util.concurrent.{ExecutorService, Executors}

import beam.sim.common.GeoUtils
import beam.sim.{BeamConfigChangesObservable, BeamHelper, LoggingEventsManager}
import beam.utils.ProfilingUtils
import beam.utils.shape.{Attributes, ShapeWriter}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.algorithm.ConvexHull
import com.vividsolutions.jts.geom.{Coordinate, Envelope, GeometryFactory, Polygon}
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.KMeansElkan
import de.lmu.ifi.dbs.elki.algorithm.clustering.kmeans.initialization.RandomUniformGeneratedInitialMeans
import de.lmu.ifi.dbs.elki.data.`type`.TypeUtil
import de.lmu.ifi.dbs.elki.data.{DoubleVector, NumberVector}
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter
import de.lmu.ifi.dbs.elki.database.{Database, StaticArrayDatabase}
import de.lmu.ifi.dbs.elki.datasource.ArrayAdapterDatabaseConnection
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.SquaredEuclideanDistanceFunction
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.mobsim.jdeqsim.Scheduler
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal

private case class CoordWithLabel(coord: Coord, label: String)
private case class ClusterInfo(size: Int, coord: Coord, linkIds: Set[Id[Link]], points: IndexedSeq[CoordWithLabel])
private case class ClusterAttributes(size: Int, links: Int) extends Attributes

object JDEQSimRunnerApp extends StrictLogging {

  // Run it using gradle: `./gradlew :execute -PmaxRAM=20 -PmainClass=beam.physsim.jdeqsim.JDEQSimRunnerApp -PappArgs=["'--config', 'test/input/sf-light/sf-light-1k.conf'"] -PlogbackCfg=logback.xml
  def main(args: Array[String]): Unit = {
    val beamHelper = new BeamHelper {}
    val (_, config) = beamHelper.prepareConfig(args, true)
    val execCfg = beamHelper.setupBeamWithConfig(config)

    val networkFile = "d:/Work/beam/ParallelJDEQSim/Beamville/output_network.xml.gz"
    val populationFile = "d:/Work/beam/ParallelJDEQSim/Beamville/0.physsimPlans.xml.gz"
    val pathToOutput = "d:/Work/beam/ParallelJDEQSim/Beamville/"

    val network = ProfilingUtils.timed(s"Read network from $networkFile", x => logger.info(x)) {
      readNetwork(networkFile)
    }

    val nClusters: Int = 3
    val clusters = ProfilingUtils.timed(s"Clustering the network: $nClusters", x => logger.info(x)) {
      clusterNetwork(network, nClusters)
    }
    writeClusters(clusters, s"network_cluster_${nClusters}.shp")
    logger.info("Written clusters")

    val linkIdToClusterIndex: Map[Id[Link], Int] = clusters.zipWithIndex.flatMap {
      case (c, idx) =>
        c.linkIds.toSeq.map(linkId => (linkId, idx))
    }.toMap

    val numOfThreads: Int = clusters.size
    val execSvc: ExecutorService = Executors.newFixedThreadPool(
      numOfThreads,
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ParallelScheduler-%d").build()
    )
    implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)
    val simulationEndTime = JDEQSimRunner.getJDEQSimConfig(execCfg.beamConfig).getSimulationEndTime
    val clusterToScheduler: Map[ClusterInfo, ParallelScheduler] = clusters.zipWithIndex.map { case (cluster, idx) =>
      val scheduler = new ParallelScheduler(simulationEndTime, s"cluster-${cluster.size}-$idx")
      cluster -> scheduler
    }.toMap

    val linkIdToScheduler: Map[Id[Link], ParallelScheduler] = clusterToScheduler.flatMap { case (cluster, scheduler) =>
      cluster.linkIds.map(lid => (lid, scheduler))
    }

    clusterToScheduler.values.foreach(s => s.setSchedulers(linkIdToScheduler))

    val javaLinkIdToScheduler: util.Map[Id[Link], Scheduler] = new util.HashMap[Id[Link], Scheduler](
      linkIdToScheduler.map { case (lid, s) => (lid, s.asInstanceOf[Scheduler]) }.asJava
    )

    val schedulers = clusterToScheduler.values.toArray

    logger.info(s"Read network with ${network.getNodes.size()} nodes and ${network.getLinks.size()} links")

    val scenario = ProfilingUtils.timed(s"Read population and plans from ${populationFile}", x => logger.info(x)) {
      readPopulation(execCfg.matsimConfig, populationFile)
    }
    val maxEndTime = scenario.getPopulation.getPersons
      .values()
      .asScala
      .map { p =>
        val endTimes = p.getPlans.asScala.flatMap { plan =>
          plan.getPlanElements.asScala.collect { case act: Activity => act.getEndTime }
        }
        if (endTimes.isEmpty) 0 else endTimes.max
      }
      .max

    val totalPlans = scenario.getPopulation.getPersons.values().asScala.map(_.getPlans.size()).sum
    logger.info(s"Read scenario with ${scenario.getPopulation.getPersons.size()} people and $totalPlans plans")
    logger.info(s"Max end time for the activity is ${maxEndTime} seconds = ${maxEndTime / 3600} hours")
    scenario.setNetwork(network)

    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(pathToOutput, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
    outputDirectoryHierarchy.createIterationDirectory(0)

    val loggingParallelEventsManager = new LoggingEventsManager(execCfg.matsimConfig)

    val physSim = new JDEQSimRunner(
      execCfg.beamConfig,
      scenario,
      scenario.getPopulation,
      outputDirectoryHierarchy,
      new java.util.HashMap[String, java.lang.Boolean](),
      new BeamConfigChangesObservable(execCfg.beamConfig),
      agentSimIterationNumber = 0,
      javaLinkIdToScheduler
    )
    physSim.simulate(currentPhysSimIter = 0, writeEvents = false, loggingParallelEventsManager)

    var shouldStop: Boolean = false
    while (!shouldStop) {
      val statuses = schedulers.map { s =>
        s.getSimTime >= simulationEndTime
      }
      val messagesInTheQueue = schedulers.filter(x => !x.isFinished).map(s => s.queuedMessages.toLong)
      val nMessagesInTheQueue = messagesInTheQueue.sum
      val haveNotEvenStarted = schedulers.count(s => s.getSimTime == 0.0)
      val haveNotFinished = schedulers.count(s => s.getSimTime >= 0.0 && s.getSimTime < simulationEndTime)
      val finished = statuses.count(x => x)
      val total = finished + haveNotEvenStarted + haveNotFinished
      logger.info(
        s"Finished ${finished}, HaveNotEvenStarted ${haveNotEvenStarted}, haveNotFinished: $haveNotFinished, Total: $total, nMessagesInTheQueue: $nMessagesInTheQueue"
      )
      Thread.sleep(5000)
    }
    logger.info("Done")
  }

  private def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(path)
    network
  }

  private def readPopulation(matsimConfig: MatsimConfig, path: String): MutableScenario = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    new PopulationReader(scenario).readFile(path)
    scenario
  }

  private def clusterNetwork(network: Network, nClusters: Int): Array[ClusterInfo] = {
    val boundingBox = new Envelope(
      new Coordinate(2859811.6678740312, 3724871.0050371685),
      new Coordinate(3047840.361184418, 3568325.9588764594)
    )
    val coordWithLinkId = network.getLinks
      .values()
      .asScala
      .flatMap { link =>
        val lid = link.getId.toString
        val start = link.getFromNode.getCoord
        val end = link.getToNode.getCoord
        val middle = new Coord((start.getX + end.getX) / 2, (start.getY + end.getY) / 2)
        Some((middle, lid))

//        if (boundingBox.contains(middle.getX, middle.getY))
//          Some((middle, lid))
//        else
//          None
      }
      .toArray

    val db = createAndInitializeDatabase(coordWithLinkId)
    val kmeans = new KMeansElkan[NumberVector](
      SquaredEuclideanDistanceFunction.STATIC,
      nClusters,
      2000,
      new RandomUniformGeneratedInitialMeans(new RandomFactory(42)),
      true
    )
    val result = kmeans.run(db)
    result.getAllClusters.asScala.zipWithIndex.map {
      case (cluster, idx) =>
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
        val linkids = coords.map(x => Id.createLinkId(x.label)).toSet
        ClusterInfo(cluster.size, new Coord(cluster.getModel.getMean), linkids, coords)
    }.toArray
  }
  private def createAndInitializeDatabase(xs: IndexedSeq[(Coord, String)]): Database = {
    val data: Array[Array[Double]] = Array.ofDim[Double](xs.length, 2)
    val labels: Array[String] = Array.ofDim[String](xs.length)
    xs.zipWithIndex.foreach {
      case ((coord, label), idx) =>
        val x = coord.getX
        val y = coord.getY
        data.update(idx, Array(x, y))
        labels.update(idx, label)
    }
    val dbc = new ArrayAdapterDatabaseConnection(data, labels)
    val db = new StaticArrayDatabase(dbc, null)
    db.initialize()
    db
  }

  private def writeClusters(
    clusters: Array[ClusterInfo],
    fileName: String
  ): Try[ShapeWriter.OriginalToPersistedFeatureIdMap] = {
    val geometryFactory: GeometryFactory = new GeometryFactory()
    val geoUtils = new GeoUtils {
      def localCRS: String = "epsg:26910"
    }

    val clusterWithConvexHull = clusters.map { c =>
      val coords = c.points.map { act =>
        new Coordinate(act.coord.getX, act.coord.getY)
      }
      val convexHullGeom = new ConvexHull(coords.toArray, geometryFactory).getConvexHull
      c -> convexHullGeom
    }
    val shapeWriter = ShapeWriter.worldGeodetic[Polygon, ClusterAttributes](fileName)
    clusterWithConvexHull.zipWithIndex.foreach {
      case ((c, geom), idx) =>
        if (geom.getNumPoints > 2) {
          val wgsCoords = geom.getCoordinates.map { c =>
            val wgsCoord = geoUtils.utm2Wgs(new Coord(c.x, c.y))
            new Coordinate(wgsCoord.getX, wgsCoord.getY)
          }
          try {
            val polygon = geometryFactory.createPolygon(wgsCoords)
            shapeWriter.add(
              polygon,
              idx.toString,
              ClusterAttributes(size = c.size, links = c.linkIds.size)
            )
          } catch {
            case NonFatal(ex) =>
              logger.error("Can't create or add", ex)
          }
        }
    }
    shapeWriter.write()
  }
}
