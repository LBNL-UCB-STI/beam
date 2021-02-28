package beam.router

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.cch.CchNative
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.gtfs.FareCalculator
import beam.router.model.{EmbodiedBeamTrip, _}
import beam.router.osm.TollCalculator
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.osmlib.{Node, OSM, OSMEntity, Way}
import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.apache.commons.io
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import java.io.{File, FileOutputStream}
import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors}
import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.io.Directory

class RoutingWorker(workerParams: R5Parameters) extends Actor with ActorLogging with MetricsSupport {

  val tempDir: File = Paths.get(System.getProperty("java.io.tmpdir"), "cchnative").toFile
  tempDir.mkdirs
  tempDir.deleteOnExit()

  if (System.getProperty("os.name").toLowerCase.contains("win")) {
    throw new IllegalStateException("Win is not supported")
  } else {
    val cchNativeLib = "libcchnative.so"
    io.FileUtils.copyInputStreamToFile(this.getClass.getClassLoader.getResourceAsStream(Paths.get("cchnative", cchNativeLib).toString), Paths.get(tempDir.toString, cchNativeLib).toFile)
    System.load(Paths.get(tempDir.getPath, cchNativeLib).toString)
  }

  def this(config: Config) {
    this(workerParams = {
      R5Parameters.fromConfig(config)
    })
  }

  private val carRouter = workerParams.beamConfig.beam.routing.carRouter

  private val noOfTimeBins = Math
    .floor(
      Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
      workerParams.beamConfig.beam.agentsim.timeBinSize
    )
    .toInt

  private val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1
    else Runtime.getRuntime.availableProcessors() - 2
  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("r5-routing-worker-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)
  private var msgs = 0
  private var firstMsgTime: Option[ZonedDateTime] = None
  log.info("RoutingWorker_v2[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  private def getNameAndHashCode: String = s"RoutingWorker_v2[${hashCode()}], Path: `${self.path}`"

  private var workAssigner: ActorRef = context.parent

  private var r5: R5Wrapper = new R5Wrapper(
    workerParams,
    new FreeFlowTravelTime,
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
  private var binToCarGraphHopper: Map[Int, GraphHopperWrapper] = _
  private var walkGraphHopper: GraphHopperWrapper = _
  private var nativeCCH: CchNative = _

  private val linksBelowMinCarSpeed =
    workerParams.networkHelper.allLinks
      .count(l => l.getFreespeed < workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    log.warning(
      "{} links are below quick_fix_minCarSpeedInMetersPerSecond, already in free-flow",
      linksBelowMinCarSpeed
    )
  }

  override def preStart(): Unit = {
    if (carRouter == "staticGH" || carRouter == "quasiDynamicGH") {
      new Directory(new File(graphHopperDir)).deleteRecursively()
      createWalkGraphHopper()
      createCarGraphHoppers(new FreeFlowTravelTime())
    }

    if (carRouter == "nativeCCH") {
      createNativeCCH()
    }

    askForMoreWork()
  }

  override def postStop(): Unit = {
    tickTask.cancel()
    execSvc.shutdown()
  }

  // Let the dispatcher on which the Future in receive will be running
  // be the dispatcher on which this actor is running.
  val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
    .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
    .toMap

//  val linkId2Weights: mutable.Map[Int, Map[String, String]] = new mutable.HashMap[Int, Map[String, String]]()

  override final def receive: Receive = {
    case "tick" =>
      firstMsgTime match {
        case Some(firstMsgTimeValue) =>
          val seconds =
            ChronoUnit.SECONDS.between(firstMsgTimeValue, ZonedDateTime.now(ZoneOffset.UTC))
          if (seconds > 0) {
            val rate = msgs.toDouble / seconds
            if (seconds > 60) {
              firstMsgTime = None
              msgs = 0
            }
            if (workerParams.beamConfig.beam.outputs.displayPerformanceTimings) {
              log.info(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            } else {
              log.debug(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            }
          }
        case None => //
      }
    case WorkAvailable =>
      workAssigner = sender
      askForMoreWork()

    case request: RoutingRequest =>
      msgs = msgs + 1
      if (firstMsgTime.isEmpty) firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val eventualResponse = Future {
        latency("request-router-time", Metrics.RegularLevel) {
          if (!request.withTransit && (carRouter == "staticGH" || carRouter == "quasiDynamicGH")) {
            // run graphHopper for only cars
            val ghCarResponse = calcCarGhRoute(request)
            // run graphHopper for only walk
            val ghWalkResponse = calcWalkGhRoute(request)

            val modesToExclude = calcExcludeModes(
              ghCarResponse.exists(_.itineraries.nonEmpty),
              ghWalkResponse.exists(_.itineraries.nonEmpty)
            )

            val response = if (modesToExclude.isEmpty) {
              r5.calcRoute(request)
            } else {
              val filteredStreetVehicles = request.streetVehicles.filter(it => !modesToExclude.contains(it.mode))
              val r5Response = if (filteredStreetVehicles.isEmpty) {
                None
              } else {
                Some(r5.calcRoute(request.copy(streetVehicles = filteredStreetVehicles)))
              }
              ghCarResponse
                .getOrElse(ghWalkResponse.get)
                .copy(
                  ghCarResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                  ghWalkResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                  r5Response.map(_.itineraries).getOrElse(Seq.empty)
                )
            }
            response
          } else if (!request.withTransit && carRouter == "nativeCCH") {
            val cchResponse = calcCarNativeCCHRoute(request)

            // FIXME same code
            val modesToExclude = calcExcludeModes(
              cchResponse.exists(_.itineraries.nonEmpty),
              successfulWalkResponse = false
            )

            val response = if (modesToExclude.isEmpty) {
              r5.calcRoute(request)
            } else {
              val filteredStreetVehicles = request.streetVehicles.filter(it => !modesToExclude.contains(it.mode))
              val r5Response = if (filteredStreetVehicles.isEmpty) {
                None
              } else {
                Some(r5.calcRoute(request.copy(streetVehicles = filteredStreetVehicles)))
              }
              cchResponse
                .getOrElse(r5Response.get)
                .copy(
                  cchResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                    r5Response.map(_.itineraries).getOrElse(Seq.empty)
                )
            }
            response
          } else {
            val resp = r5.calcRoute(request)

//            val r5Res = resp.itineraries.flatMap(_.beamLegs).map(_.travelPath).flatMap(_.linkIds)
//            if (request.streetVehicles.exists(_.mode == Modes.BeamMode.CAR) && r5Res.nonEmpty) {
//              this.synchronized {
//                val origin = workerParams.geo.utm2Wgs(request.originUTM)
//                val destination = workerParams.geo.utm2Wgs(request.destinationUTM)
//
//                val cchResponse = nativeCCH.route(origin.getX, origin.getY, destination.getX, destination.getY)
//
//                val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
//                def getFullLine(list: Seq[Int]) = {
//                  val geomStr = list.map { id =>
//                    cur.seek(id)
//                    cur.getGeometry.toText.replaceAll("LINESTRING", "")
//                  }.mkString(",")
//                  s"multilinestring(${geomStr})"
//                }
//
//                val r5Line = getFullLine(r5Res)

//                val cchNodes = cchResponse.getNodes.asScala.map(_.toLong)
//                val links = new ArrayBuffer[Int]()
//                val nodesIter = cchNodes.iterator
//                var prevNode = nodesIter.next()
//                while (nodesIter.hasNext) {
//                  val node = nodesIter.next()
//                  links += nodes2Link(prevNode, node).toInt
//                  prevNode = node
//                }

//                val cchLine = getFullLine(links)
//                val cchLine = getFullLine(cchResponse.getLinks.asScala.map(_.toInt))
//                r5Line == cchLine
//              }
//            }
            resp
          }
        }
      }
      eventualResponse.recover {
        case e =>
          log.error(e, "calcRoute failed")
          RoutingFailure(e, request.requestId)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      RouterWorkerStats.printStats()
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(newTravelTime)
      } else if (carRouter == "nativeCCH") {
        rebuildNativeCCHWeights(newTravelTime)
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info("{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      RouterWorkerStats.printStats()
      val newTravelTime =
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(workerParams.beamConfig.beam.agentsim.timeBinSize, map)
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(newTravelTime)
      } else if (carRouter == "nativeCCH") {
        rebuildNativeCCHWeights(newTravelTime)
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info(
        "{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
        getNameAndHashCode,
        map.keySet().size()
      )
      askForMoreWork()

    case EmbodyWithCurrentTravelTime(
        leg: BeamLeg,
        vehicleId: Id[Vehicle],
        vehicleTypeId: Id[BeamVehicleType],
        embodyRequestId: Int
        ) =>
      val response: RoutingResponse = r5.embodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, embodyRequestId)
      sender ! response
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  private def createWalkGraphHopper(): Unit = {
    log.info("Init GH Walk")
    GraphHopperWrapper.createWalkGraphDirectoryFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir
    )

    walkGraphHopper = new WalkGraphHopperWrapper(graphHopperDir, workerParams.geo, id2Link)
  }

  private def createCarGraphHoppers(travelTime: TravelTime): Unit = {
    log.info("Init GH Car")
    // Clean up GHs variable and than calculate new ones
    binToCarGraphHopper = Map()
    new Directory(new File(carGraphHopperDir)).deleteRecursively()

    val graphHopperInstances = if (carRouter == "quasiDynamicGH") noOfTimeBins else 1

    val maxSpeed: Double = workerParams.networkHelper.allLinks.map(_.getFreespeed).max
    val minSpeed = workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond

    val futures = (0 until graphHopperInstances).map { i =>
      Future {
        val ghDir = Paths.get(carGraphHopperDir, i.toString).toString

        val wayId2TravelTime =
            workerParams.networkHelper.allLinks.toSeq
              .map { l =>
                val edge = workerParams.transportNetwork.streetLayer.edgeStore.getCursor(l.getId.toString.toInt)
                val minTravelTime = (edge.getLengthM / maxSpeed).ceil.toInt
                val maxTravelTime = (edge.getLengthM / minSpeed).ceil.toInt
                val physSimTravelTime = travelTime.getLinkTravelTime(l, i * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null)
                val linkTravelTime = Math.max(physSimTravelTime, minTravelTime)
                val weight = Math.min(linkTravelTime, maxTravelTime)

                l.getId.toString.toLong -> weight
              }
              .toMap

        GraphHopperWrapper.createCarGraphDirectoryFromR5(
          carRouter,
          workerParams.transportNetwork,
          new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
          ghDir,
          wayId2TravelTime
        )

        i -> new CarGraphHopperWrapper(
          carRouter,
          ghDir,
          workerParams.geo,
          workerParams.vehicleTypes,
          workerParams.fuelTypePrices,
          wayId2TravelTime,
          id2Link
        )
      }
    }

    val s = System.currentTimeMillis()
    binToCarGraphHopper = Await.result(Future.sequence(futures), 20.minutes).toMap
    val e = System.currentTimeMillis()
    log.info(s"GH built in ${e - s} ms")
  }

  private def rebuildNativeCCHWeights(newTravelTime: TravelTime): Unit = {
    val s = System.currentTimeMillis()
    nativeCCH.lock()

    val maxSpeed: Double = workerParams.networkHelper.allLinks.map(_.getFreespeed).max
    val minSpeed = workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond
//    val futures = (0 until noOfTimeBins).map { bin =>
    (0 until noOfTimeBins).foreach { bin =>
//      Future {
        val wayId2TravelTime =
          workerParams.networkHelper.allLinks.toSeq
            .map { l =>
              val linkId = l.getId.toString
              val edge = workerParams.transportNetwork.streetLayer.edgeStore.getCursor(linkId.toInt)
              val minTravelTime = (edge.getLengthM / maxSpeed).ceil.toInt
              val maxTravelTime = (edge.getLengthM / minSpeed).ceil.toInt
              val physSimTravelTime = newTravelTime.getLinkTravelTime(l, bin * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null)
              val linkTravelTime = Math.max(physSimTravelTime, minTravelTime)
              val weight = Math.min(linkTravelTime, maxTravelTime)
              linkId -> weight.toString
//                    newTravelTime.getLinkTravelTime(l, bin * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null).toLong.toString
            }
            .toMap

//        linkId2Weights.put(bin, wayId2TravelTime)
        nativeCCH.createBinQueries(bin, wayId2TravelTime.asJava)
//      }
    }
//    Await.result(Future.sequence(futures), 20.minutes)
    nativeCCH.unlock()
    val e = System.currentTimeMillis()
    log.info(s"Cch native rebuilt weights in ${e - s} ms")
  }

  private def createNativeCCH(): Unit = {
    val cchOsm = new OSM(null)
    val r5Osm = new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile)
    val vertexCur = workerParams.transportNetwork.streetLayer.vertexStore.getCursor

    def addNode(nodeId: Long): Node = {
      vertexCur.seek(nodeId.toInt)
      val node = new Node(vertexCur.getLat, vertexCur.getLon)
      cchOsm.nodes.put(nodeId, node)
    }

    val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
    for (idx <- 0 until workerParams.transportNetwork.streetLayer.edgeStore.nEdges by 1) {
      cur.seek(idx)

      if (cur.allowsStreetMode(StreetMode.CAR)) {
        val fromNodeId = cur.getFromVertex.toLong
        val toNodeId = cur.getToVertex.toLong
        if (!cchOsm.nodes.containsKey(fromNodeId)) {
          addNode(fromNodeId)
        }

        if (!cchOsm.nodes.containsKey(toNodeId)) {
          addNode(toNodeId)
        }

        val newWay = new Way()
        newWay.nodes = Array(fromNodeId, toNodeId)

        val osmWay = r5Osm.ways.get(cur.getOSMID)
        if (osmWay != null) {
//          if (r5Osm.nodes.containsKey(osmWay.nodes.head)) {
//            val fromNode = r5Osm.nodes.get(osmWay.nodes.head)
//            if (fromNode.tags != null) {
//              fromNode.tags.forEach((tag: OSMEntity.Tag) => {
//                cchOsm.nodes.get(fromNodeId).addTag(tag.key, tag.value)
//              })
//            }
//          }
//
//          if (r5Osm.nodes.containsKey(osmWay.nodes.last)) {
//            val toNode = r5Osm.nodes.get(osmWay.nodes.last)
//            if (toNode.tags != null) {
//              toNode.tags.forEach((tag: OSMEntity.Tag) => {
//                cchOsm.nodes.get(toNodeId).addTag(tag.key, tag.value)
//              })
//            }
//          }

          osmWay.tags.forEach((tag: OSMEntity.Tag) => {
            newWay.addOrReplaceTag(tag.key, tag.value)
          })
          newWay.addOrReplaceTag("oneway", "yes")
        } else {
          newWay.addOrReplaceTag("highway", "trunk")
          newWay.addOrReplaceTag("oneway", "yes")
        }

        cchOsm.ways.put(idx.toLong, newWay)
      }
    }
    val osmFile = Paths.get(tempDir.toString, "cch-generated.osm.pbf").toString
    cchOsm.writePbf(new FileOutputStream(osmFile))

    val s = System.currentTimeMillis()
    log.info("Init CchNative")
    nativeCCH = new CchNative()
    nativeCCH.init(osmFile)

//    val futures = (0 until noOfTimeBins).map { bin =>
////    (0 until noOfTimeBins).map { bin =>
//      Future {
//      linkId2Weights.put(bin, new HashMap[String, String]())
//        nativeCCH.createBinQueries(bin, Map[String, String]().asJava)
//      }
//    }
    rebuildNativeCCHWeights(new FreeFlowTravelTime())

//    Await.result(Future.sequence(futures), 20.minutes)

    val e = System.currentTimeMillis()
    log.info(s"Cch native built in ${e - s} ms")

//    val r2 = nativeCCH.route(5, -122.43244898381829, 37.77491557097691, -122.45382120000001, 37.7704974)
//    def getFullLine(list: Seq[Int]) = {
//      val geomStr = list.map { id =>
//        cur.seek(id)
//        cur.getGeometry.toText.replaceAll("LINESTRING", "")
//      }.mkString(",")
//      s"multilinestring(${geomStr})"
//    }
//
//    val r1Line = getFullLine(r1.getLinks.asScala.map(_.toInt))
//    val r2Line = getFullLine(r2.getLinks.asScala.map(_.toInt))
//    if (r1Line == r2Line) {
//      System.out.println("<>")
//    }

  }

  def escapeHTML(s: String): String = {
    val out = new StringBuilder(Math.max(16, s.length))
    for (i <- 0 until s.length) {
      val c = s.charAt(i)
      if (c > 127 || c == '"' || c == '\'' || c == '<' || c == '>' || c == '&') {
        out.append("&#")
        out.append(c.toInt)
        out.append(';')
      }
      else out.append(c)
    }
    out.toString
  }

  private def calcCarNativeCCHRoute(req: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (req.streetVehicles.exists(_.mode == mode)) {
      try {
        val request = req.copy(streetVehicles = req.streetVehicles.filter(_.mode == mode))
        val origin = workerParams.geo.utm2Wgs(request.originUTM)
        val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

        val bin = Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        val cchResponse = nativeCCH.route(bin, origin.getX, origin.getY, destination.getX, destination.getY)
        val streetVehicle = request.streetVehicles.head
        val times = cchResponse.getTimes.asScala.map(_.toDouble)

        if (times.nonEmpty && cchResponse.getLinks.size() > 1 && cchResponse.getDepTime != 0L) {
          //        if (times.nonEmpty) {
          //-----
          //          val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
          //          var cchTrueDistance = 0.0D
          //          cchResponse.getLinks.asScala.foreach { linkId =>
          //            cur.seek(linkId.toInt)
          //            cchTrueDistance += cur.getLengthM
          //          }
          //
          //          val weights = linkId2Weights(bin)
          //          val times2 = cchResponse.getLinks.asScala.map(linkId => weights.getOrElse(linkId, "0")).map(_.toDouble)
          //          val depTime = times2.sum.toInt
          //-----

          val beamTotalTravelTime = cchResponse.getDepTime.toInt - times.head.toInt
          //          val beamTotalTravelTime = depTime - times2.head.toInt
          val beamLeg = BeamLeg(
            request.departureTime,
            mode,
            beamTotalTravelTime,
            BeamPath(
              cchResponse.getLinks.asScala.map(_.toInt).toIndexedSeq,
              times.toIndexedSeq,
              None,
              SpaceTime(origin, request.departureTime),
              SpaceTime(destination, request.departureTime + beamTotalTravelTime),
              //              cchTrueDistance
              cchResponse.getDistance
            )
          )
          val vehicleType = workerParams.vehicleTypes(streetVehicle.vehicleTypeId)
          val alternative = EmbodiedBeamTrip(
            IndexedSeq(
              EmbodiedBeamLeg(
                beamLeg,
                streetVehicle.id,
                streetVehicle.vehicleTypeId,
                asDriver = true,
                cost = DrivingCost.estimateDrivingCost(beamLeg.travelPath.distanceInM, beamLeg.duration, vehicleType, workerParams.fuelTypePrices(vehicleType.primaryFuelType)),
                unbecomeDriverOnCompletion = true
              )
            )
          )

          val cchResp = RoutingResponse(Seq(alternative), request.requestId, Some(request), isEmbodyWithCurrentTravelTime = false)

          //          val weights = linkId2Weights(bin)
          //          val cchTrueTimes = if (weights.nonEmpty) {
          //            cchResponse.getLinks.asScala.map(linkId => weights.getOrElse(linkId, "0")).map(_.toDouble)
          //          } else {
          //            List(1.0)
          //          }
          //
          //          val cur = workerParams.transportNetwork.streetLayer.edgeStore.getCursor
          //          def getFullLine(list: Seq[Int]) = {
          //            val geomStr = list.map { id =>
          //              cur.seek(id)
          //              cur.getGeometry.toText.replaceAll("LINESTRING", "")
          //            }.mkString(",")
          //            s"multilinestring(${geomStr})"
          //          }
          //
          //          val r5Response = r5.calcRoute(request)
          //
          //          val r5TrueTimes = if (weights.nonEmpty) {
          //            r5Response.itineraries.head.legs.head.beamLeg.travelPath.linkIds.map(linkId => weights.getOrElse(linkId.toString, "-1")).map(_.toDouble)
          //          } else {
          //            List(1.0)
          //          }
          //
          //          var cchTrueDistance = 0.0
          //          cchResp.itineraries.flatMap(_.beamLegs).flatMap(_.travelPath.linkIds).foreach { linkId =>
          //            cur.seek(linkId)
          //            cchTrueDistance += cur.getLengthM
          //          }
          //
          //          val r5TrueDistance = r5Response.itineraries
          //            .flatMap(_.beamLegs)
          //            .flatMap(_.travelPath.linkIds)
          //            .map { linkId =>
          //              cur.seek(linkId)
          //              cur.getLengthM
          //            }
          //
          //          val cchTrueDuration = cchTrueTimes.sum
          //          val cchDuration = cchResp.itineraries.flatMap(_.beamLegs).map(l => l.endTime - l.startTime)
          //          val r5Duration = r5Response.itineraries.flatMap(_.beamLegs).map(l => l.endTime - l.startTime)
          //          val r5TrueDuration = r5TrueTimes.sum
          //          val cchDistance = cchResp.itineraries.flatMap(_.beamLegs).map(_.travelPath.distanceInM)
          //          val r5Distance = r5Response.itineraries.flatMap(_.beamLegs).map(_.travelPath.distanceInM)
          //          val cchSpeed = cchDistance.head / cchDuration.head
          //          val cchTrueSpeed = cchTrueDistance / cchTrueDuration
          //          val r5Speed = r5Distance.head / r5Duration.head
          //          val r5TrueSpeed = r5TrueDistance.sum / r5TrueDuration
          //          if (cchSpeed == r5Speed || cchTrueSpeed == r5TrueSpeed) {
          //            System.out.println("")
          //          }
          //          val cchLine = getFullLine(cchResp.itineraries.flatMap(_.beamLegs).flatMap(_.travelPath.linkIds))
          //          val r5Line = getFullLine(r5Response.itineraries.flatMap(_.beamLegs).flatMap(_.travelPath.linkIds))
          //
          //          if (cchLine == r5Line) {
          //          }

          Some(cchResp)
        } else Some(RoutingResponse(Seq(), req.requestId, Some(req), isEmbodyWithCurrentTravelTime = false))
      } catch {
        case _: Exception => Some(RoutingResponse(Seq(), req.requestId, Some(req), isEmbodyWithCurrentTravelTime = false))
      }
    } else Some(RoutingResponse(Seq(), req.requestId, Some(req), isEmbodyWithCurrentTravelTime = false))
  }

  private def calcCarGhRoute(request: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (request.streetVehicles.exists(_.mode == mode)) {
      val idx =
        if (carRouter == "quasiDynamicGH")
          Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        else 0

      Some(binToCarGraphHopper(idx).calcRoute(
        request.copy(streetVehicles = request.streetVehicles.filter(_.mode == mode))
      ))
    } else None
  }

  private def calcWalkGhRoute(request: RoutingRequest): Option[RoutingResponse] = {
    val mode = Modes.BeamMode.WALK
    if (request.streetVehicles.exists(_.mode == mode)) {
      Some(
        walkGraphHopper.calcRoute(request.copy(streetVehicles = request.streetVehicles.filter(_.mode == mode)))
      )
    } else None
  }

  private def calcExcludeModes(successfulCarResponse: Boolean, successfulWalkResponse: Boolean) = {
    if (successfulCarResponse && successfulWalkResponse) {
      List(CAR, WALK)
    } else if (successfulCarResponse) {
      List(CAR)
    } else if (successfulWalkResponse) {
      List(WALK)
    } else {
      List()
    }
  }
}

object RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 1.38

  // 3.1 mph -> 1.38 meter per second, changed from 1 mph
  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    networkHelper: NetworkHelper,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator
  ): Props = Props(
    new RoutingWorker(
      R5Parameters(
        beamScenario.beamConfig,
        transportNetwork,
        beamScenario.vehicleTypes,
        beamScenario.fuelTypePrices,
        beamScenario.ptFares,
        new GeoUtilsImpl(beamScenario.beamConfig),
        beamScenario.dates,
        networkHelper,
        fareCalculator,
        tollCalculator
      )
    )
  )

  case class R5Request(
    from: Coord,
    to: Coord,
    time: Int,
    directMode: LegMode,
    accessMode: LegMode,
    withTransit: Boolean,
    egressMode: LegMode,
    timeValueOfMoney: Double,
    beamVehicleTypeId: Id[BeamVehicleType]
  )

  def createBushwackingBeamLeg(
    atTime: Int,
    startUTM: Location,
    endUTM: Location,
    geo: GeoUtils
  ): BeamLeg = {
    val distanceInMeters = GeoUtils.minkowskiDistFormula(startUTM, endUTM) //changed from geo.distUTMInMeters(startUTM, endUTM)
    val bushwhackingTime = Math.round(distanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
    val path = BeamPath(
      Vector(),
      Vector(),
      None,
      SpaceTime(geo.utm2Wgs(startUTM), atTime),
      SpaceTime(geo.utm2Wgs(endUTM), atTime + bushwhackingTime.toInt),
      distanceInMeters
    )
    BeamLeg(atTime, WALK, bushwhackingTime.toInt, path)
  }

  def createBushwackingTrip(
    originUTM: Location,
    destUTM: Location,
    atTime: Int,
    body: StreetVehicle,
    geo: GeoUtils
  ): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      Vector(
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(atTime, originUTM, destUTM, geo),
          body.id,
          body.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = true
        )
      )
    )
  }

  class StopVisitor(
    val streetLayer: StreetLayer,
    val dominanceVariable: StreetRouter.State.RoutingVariable,
    val maxStops: Int,
    val minTravelTimeSeconds: Int,
    val destinationSplit: Split
  ) extends RoutingVisitor {
    private val NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.getNoEntryKey
    val stops: TIntIntMap = new TIntIntHashMap
    private var s0: StreetRouter.State = _
    private val destinationSplitVertex0 = if (destinationSplit != null) destinationSplit.vertex0 else -1
    private val destinationSplitVertex1 = if (destinationSplit != null) destinationSplit.vertex1 else -1

    override def visitVertex(state: StreetRouter.State): Unit = {
      s0 = state
      val stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex)
      if (stop != NO_STOP_FOUND) {
        if (state.getDurationSeconds < minTravelTimeSeconds) return
        if (!stops.containsKey(stop) || stops.get(stop) > state.getRoutingVariable(dominanceVariable))
          stops.put(stop, state.getRoutingVariable(dominanceVariable))
      }
    }

    override def shouldBreakSearch: Boolean =
      stops.size >= this.maxStops || s0.vertex == destinationSplitVertex0 || s0.vertex == destinationSplitVertex1
  }

  class EdgeInfo(
                  val edgeId: Int,
                  val fromNode: Int,
                  val toNode: Int
                )
}

object RouterWorkerStats extends LazyLogging {
  private val senders = new ConcurrentHashMap[String, AtomicInteger]()
  private val woTransitWithCarMode = new ConcurrentHashMap[String, AtomicInteger]()

  def add(str: String, req: RoutingRequest): Unit = {
    if (senders.containsKey(str)) {
      senders.get(str).incrementAndGet()
    } else {
      this.synchronized {
        if (senders.containsKey(str)) {
          senders.get(str).incrementAndGet()
        } else {
          senders.put(str, new AtomicInteger(1))
        }
      }
    }

    if (!req.withTransit && req.streetVehicles.exists(_.mode == BeamMode.CAR)) {
      if (woTransitWithCarMode.containsKey(str)) {
        woTransitWithCarMode.get(str).incrementAndGet()
      } else {
        this.synchronized {
          if (woTransitWithCarMode.containsKey(str)) {
            woTransitWithCarMode.get(str).incrementAndGet()
          } else {
            woTransitWithCarMode.put(str, new AtomicInteger(1))
          }
        }
      }
    }
  }

  def printStats(): Unit = {
    logger.info("--------------- Router Worker Stats --------------")
    logger.info("--------------- Common ---------------------------")
    logger.info(senders.asScala.map { case (str, i) => s"$str -> $i" }.mkString(";"))
    logger.info("--------------- Without Transit ---------------------------")
    logger.info(woTransitWithCarMode.asScala.map { case (str, i) => s"$str -> $i" }.mkString(";"))
  }
}