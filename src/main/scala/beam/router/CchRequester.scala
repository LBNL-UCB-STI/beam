package beam.router

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.events.SpaceTime
import beam.cch.CchNative
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.utils.ParquetReader
import com.conveyal.osmlib.{Node, OSM, OSMEntity, Way}
import com.conveyal.r5.profile.StreetMode
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.util.Utf8
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import scala.collection.JavaConverters._
import beam.utils.json.AllNeededFormats._

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

// You can run it as:
//  - App with Main method in IntelliJ IDEA. You need to provide config as Program Arguments: `--config test/input/texas/austin-prod-100k.conf`
//  - Run it from gradle: `./gradlew :execute -PmainClass=beam.router.R5Requester -PmaxRAM=4 -PappArgs="['--config', 'test/input/texas/austin-prod-100k.conf']"`
object CchRequester extends BeamHelper with LazyLogging {
  val tempDir: File = Paths.get(System.getProperty("java.io.tmpdir"), "cchnative").toFile
  tempDir.mkdirs
  tempDir.deleteOnExit()

  if (System.getProperty("os.name").toLowerCase.contains("win")) {
    throw new IllegalStateException("Win is not supported")
  } else {
    val cchNativeLib = "libcchnative.so"
    org.apache.commons.io.FileUtils.copyInputStreamToFile(this.getClass.getClassLoader.getResourceAsStream(Paths.get("cchnative", cchNativeLib).toString), Paths.get(tempDir.toString, cchNativeLib).toFile)
    System.load(Paths.get(tempDir.getPath, cchNativeLib).toString)
  }

  private var nativeCCH: CchNative = _
  private val withTransit = new AtomicInteger(0)
  private val woTransit = new AtomicInteger(0)
  private val woTransitWithCarOnly = new AtomicInteger(0)
  private val woTransitWithCar = new AtomicInteger(0)
  private val woTransitWOCar = new AtomicInteger(0)
  private val mode2Counter: mutable.Map[BeamMode, Int] = new mutable.HashMap[BeamMode, Int]()

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

//    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
//    createNativeCCH(workerParams)

    val portionSize = 500000
    val counter = new AtomicInteger(0)
    val totalCounter = new AtomicInteger(0)
    var i = 0
    do {
      counter.set(0)
      getRequests(i * portionSize, portionSize).foreach { req =>
        calcStats(req)
//        calcCarNativeCCHRoute(workerParams, req)
        counter.incrementAndGet()
      }

      totalCounter.addAndGet(counter.get())
      logger.info(s"${counter.get()} processed")
      i += 1
    } while (counter.get() != 0)

    logger.info(s"All ${totalCounter.get()} request are processed")
    logger.info("----- STATS -----")
    logger.info("- global counters -")
    logger.info(s"Total with transit requests: ${withTransit.get()}")
    logger.info(s"Total without transit requests: ${woTransit.get()}")
    logger.info("- without transit details -")
    logger.info(s"Total without transit with car only requests: ${woTransitWithCarOnly.get()}")
    logger.info(s"Total without transit with car requests: ${woTransitWithCar.get()}")
    logger.info(s"Total without transit without car requests: ${woTransitWOCar.get()}")
    logger.info("- modes details -")
    mode2Counter.foreach{ case (mode, counter) =>
      logger.info(s"${mode.toString}: $counter")
    }
  }

  private def calcStats(req: RoutingRequest) = {
    req.streetVehicles.map(_.mode).foreach { mode =>
      mode2Counter.get(mode) match {
        case Some(value) => mode2Counter.put(mode, value + 1)
        case None => mode2Counter.put(mode, 1)
      }
    }

    if (req.withTransit) {
      withTransit.incrementAndGet()
    } else {
      woTransit.incrementAndGet()
      val mode = Modes.BeamMode.CAR
      if (req.streetVehicles.exists(_.mode == mode)) {
        if (req.streetVehicles.exists(_.mode != mode)) {
          woTransitWithCar.incrementAndGet()
        } else {
          woTransitWithCarOnly.incrementAndGet()
        }
      } else {
        woTransitWOCar.incrementAndGet()
      }
    }
  }

  private def createNativeCCH(workerParams: R5Parameters): Unit = {
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
    logger.info("Init CchNative")
    nativeCCH = new CchNative()
    nativeCCH.init(osmFile)

    rebuildNativeCCHWeights(workerParams, new FreeFlowTravelTime())

    val e = System.currentTimeMillis()
    logger.info(s"Cch native built in ${e - s} ms")
  }

  private def rebuildNativeCCHWeights(workerParams: R5Parameters, newTravelTime: TravelTime): Unit = {
    val noOfTimeBins = Math
      .floor(
        Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
          workerParams.beamConfig.beam.agentsim.timeBinSize
      )
      .toInt
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
          }
          .toMap

      nativeCCH.createBinQueries(bin, wayId2TravelTime.asJava)
      //      }
    }
    //    Await.result(Future.sequence(futures), 20.minutes)
    nativeCCH.unlock()
    val e = System.currentTimeMillis()
    logger.info(s"Cch native rebuilt weights in ${e - s} ms")
  }

  private def getRequests(drop: Int, take: Int): Array[RoutingRequest] = {
    val requestRecords = {
      val (it, toClose) = ParquetReader.read(
        "/home/crixal/Downloads/0.routingRequest.parquet"
      )
      try {
        it.drop(drop).take(take).toArray
      } finally {
        toClose.close()
      }
    }
    logger.info(s"requestRecords: ${requestRecords.length}")

    val requests = requestRecords.map { req =>
      val reqJsonStr = new String(req.get("requestAsJson").asInstanceOf[Utf8].getBytes, StandardCharsets.UTF_8)
      io.circe.parser.parse(reqJsonStr).right.get.as[RoutingRequest].right.get
    }
    logger.info(s"requests: ${requests.length}")
    requests
  }

  private def calcCarNativeCCHRoute(workerParams: R5Parameters, req: RoutingRequest) = {
    val mode = Modes.BeamMode.CAR
    if (req.streetVehicles.exists(_.mode == mode)) {
      try {
        val request = req.copy(streetVehicles = req.streetVehicles.filter(_.mode == mode))
        val origin = workerParams.geo.utm2Wgs(request.originUTM)
        val destination = workerParams.geo.utm2Wgs(request.destinationUTM)

        val bin = Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        try {
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
          case e: Throwable => e.printStackTrace()
          case e: Error => e.printStackTrace()
        }
      } catch {
        case _: Exception => Some(RoutingResponse(Seq(), req.requestId, Some(req), isEmbodyWithCurrentTravelTime = false))
      }
    } else Some(RoutingResponse(Seq(), req.requestId, Some(req), isEmbodyWithCurrentTravelTime = false))
  }
}
