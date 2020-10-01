package beam.utils.map

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import beam.router.BeamRouter.RoutingRequest
import beam.router.{BeamRouter, FreeFlowTravelTime}
import beam.router.Modes.BeamMode.{DRIVE_TRANSIT, WALK_TRANSIT}
import beam.router.Modes.{toR5StreetMode, BeamMode}
import beam.router.R5Requester.prepareConfig
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.common.GeoUtils
import beam.utils.{NetworkHelperImpl, ParquetReader}
import beam.utils.csv.CsvWriter
import beam.utils.json.AllNeededFormats._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.streets.{StreetLayer, StreetRouter}
import org.apache.avro.generic.GenericRecord
import org.apache.avro.util.Utf8
import org.matsim.api.core.v01.Coord

import scala.collection.JavaConverters._

object NewYorkRouteDebugging {
  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def walkWithOneItinerary(record: GenericRecord): Boolean = {
    val isWithinTransitTime = record.get("startPoint_time").asInstanceOf[Int] >= TimeUnit.HOURS.toSeconds(6) && record
      .get("startPoint_time")
      .asInstanceOf[Int] <= TimeUnit.HOURS.toSeconds(22)
    val isEmptyLinkIds = Option(record.get("linkIds").asInstanceOf[Utf8])
      .forall(x => new String(x.getBytes, StandardCharsets.UTF_8).isEmpty)
    record.get("itineraries").asInstanceOf[Int] == 1 && record
      .get("tripClassifier")
      .toString == "walk" && isWithinTransitTime //&& isEmptyLinkIds
  }

  def main(args: Array[String]): Unit = {
    val requests = getRequests

    val runArgs = args
    val (_, cfg) = prepareConfig(runArgs, isConfigArgRequired = true)

    val (workerParams: R5Parameters, maybeNetworks2) = R5Parameters.fromConfig(cfg)
    println(s"baseDate: ${workerParams.dates.localBaseDate}")
    val r5Wrapper: R5Wrapper = new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)
    val r5Wrapper2: Option[R5Wrapper] = maybeNetworks2.map {
      case (transportNetwork, network) =>
        new R5Wrapper(
          workerParams.copy(transportNetwork = transportNetwork, networkHelper = new NetworkHelperImpl(network)),
          new FreeFlowTravelTime,
          travelTimeNoiseFraction = 0
        )
    }
    val ppQuery = new PointToPointQuery(workerParams.transportNetwork)

    var totalWalkTransitsByPointToPointQuery: Int = 0

//    showDatesAndServices(workerParams)
//    writeTransitServiceInfo(workerParams)

    val withSubwayTransit1 = new AtomicInteger(0)
    val withSubwayTransit2 = new AtomicInteger(0)
    val nDone = new AtomicInteger(0)
    val s = System.currentTimeMillis()
    requests.par.foreach { req =>
      val resp1 = r5Wrapper.calcRoute(req)
      val resp = r5Wrapper2.map(wrapper => wrapper.calcRoute(req)).getOrElse(resp1)
      if (subwayPresented(resp1)) {
        withSubwayTransit1.incrementAndGet()
      }
      if (subwayPresented(resp)) {
        withSubwayTransit2.incrementAndGet()
      }
      val idx = nDone.getAndIncrement()
      if (idx % 1000 == 0) {
        val diffS = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - s)
        val avg = idx.toDouble / diffS
        println(
          s"Done $idx out of ${requests.length}. Total time: ${diffS} seconds, AVG per second: ${avg}," +
          s" withSubwayTransit1: ${withSubwayTransit1} and withSubwayTransit2: $withSubwayTransit2"
        )
      }

    //      val startWgs = geoUtils.utm2Wgs(req.originUTM)
//      val endWgs = geoUtils.utm2Wgs(req.destinationUTM)
//
//      val streetRouter =
//        routeWithStreetRouter(r5Wrapper, workerParams.transportNetwork.streetLayer, BeamMode.WALK, startWgs, endWgs)
//      println(s"streetRouter: $streetRouter")
//      val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
//      println(s"lastState: $lastState")
//
//      if (!resp.itineraries.exists(x => x.tripClassifier == WALK_TRANSIT)) {
//        val resp2 = r5Wrapper.calcRoute(req)
//        println(resp2)
//      }
//      if (!resp.itineraries.exists(x => x.tripClassifier == WALK_TRANSIT)) {
//
//        val r5Req = r5Wrapper.createProfileRequest
//        r5Req.fromLon = startWgs.getX
//        r5Req.fromLat = startWgs.getY
//        r5Req.toLon = endWgs.getX
//        r5Req.toLat = endWgs.getY
//        r5Req.transitModes = util.EnumSet.allOf(classOf[TransitModes])
//        r5Req.directModes = util.EnumSet.of(LegMode.WALK)
//        r5Req.accessModes = util.EnumSet.of(LegMode.WALK)
//        r5Req.egressModes = util.EnumSet.of(LegMode.WALK)
//        r5Req.fromTime = req.departureTime
//        r5Req.toTime = req.departureTime + 61
//
//        val plan = ppQuery.getPlan(r5Req)
//        val withTransits = plan.options.asScala.filter(x => Option(x.transit).map(_.size()).getOrElse(0) > 0)
//        if (withTransits.nonEmpty) totalWalkTransitsByPointToPointQuery += 1
//
//        println(s"Request ${req.requestId}")
//        println(s"Plan size: ${plan.options.size()}, withTransits: ${withTransits.size}")
//
//        println(s"Plan: ${plan}")
////        val transitLink = s"http://localhost:8080/transit_plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=WALK&full=false&departureTime=" + req.departureTime
//        val planLink =
//          s"http://localhost:8080/plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=WALK&full=false"
//        println(s"Link to R5 PointToPointRouterServer: ${planLink}")
//      }
    }
    println(s"${withSubwayTransit1} out of ${requests.length} have transit")

  }

  private def subwayPresented(resp: BeamRouter.RoutingResponse) = {
    resp.itineraries.exists(
      x =>
        (x.tripClassifier == WALK_TRANSIT || x.tripClassifier == DRIVE_TRANSIT) & x.legs
          .exists(leg => leg.beamLeg.mode == beam.router.Modes.BeamMode.SUBWAY)
    )
  }

  def getRequestsFakeWalkers: Array[RoutingRequest] = {
    val onlyWalkResponseRecords = {
      val (it, toClose) = ParquetReader.read(
        "D:/Work/beam/NewYork/Runs/new-york-200k-baseline-test-transit-feb__2020-08-23_18-59-19_gev/0.routingResponse.parquet"
      )
      try {
        it.filter(walkWithOneItinerary).toArray
      } finally {
        toClose.close()
      }
    }

    val requestIds = onlyWalkResponseRecords.map { resp =>
      resp.get("requestId").asInstanceOf[Int]
    }.toSet
    val requestRecords = {
      val (it, toClose) = ParquetReader.read(
        "C:\\Users\\dimao\\Downloads\\ny-baseline.routingRequest.parquet"
      )
      try {
        it.filter(
            req =>
              req.get("withTransit").asInstanceOf[Boolean] && requestIds
                .contains(req.get("requestId").asInstanceOf[Int])
          )
          .toArray
      } finally {
        toClose.close()
      }
    }
    println(s"requestRecords: ${requestRecords.length}")
    println(s"onlyWalkResponseRecords: ${onlyWalkResponseRecords.length}")

    val requests = requestRecords.map { req =>
      val reqJsonStr = new String(req.get("requestAsJson").asInstanceOf[Utf8].getBytes, StandardCharsets.UTF_8)
      io.circe.parser.parse(reqJsonStr).right.get.as[RoutingRequest].right.get
    }
    println(s"requests: ${requests.length}")
    requests
  }

  def getRequests: Array[RoutingRequest] = {
    val requestRecords = {
      val (it, toClose) = ParquetReader.read(
        "d:/Work/beam/NewYork/Runs/new-york-200k-fixed-walk-high-transit-capacity__2020-08-09_13-23-50_ayk/0.routingRequest.parquet"
      )
      try {
        it.take(100000).toArray
      } finally {
        toClose.close()
      }
    }
    println(s"requestRecords: ${requestRecords.length}")

    val requests = requestRecords.map { req =>
      val reqJsonStr = new String(req.get("requestAsJson").asInstanceOf[Utf8].getBytes, StandardCharsets.UTF_8)
      io.circe.parser.parse(reqJsonStr).right.get.as[RoutingRequest].right.get
    }
    println(s"requests: ${requests.length}")
    requests
  }

  private def writeTransitServiceInfo(workerParams: R5Parameters): Unit = {
    val headers = Vector(
      "service_id",
      "is_working",
      "feed_id",
      "monday",
      "tuesday",
      "wednesday",
      "thursday",
      "friday",
      "saturday",
      "sunday",
      "start_date",
      "end_date"
    )
    val csvWriter = new CsvWriter("services.csv", headers)

    def escape(str: String): String = "\"" + str + "\""

    val activeServices =
      workerParams.transportNetwork.transitLayer.getActiveServicesForDate(workerParams.dates.localBaseDate)
    workerParams.transportNetwork.transitLayer.services.asScala.zipWithIndex.foreach {
      case (svc, idx) =>
        val isWorking = activeServices.get(idx).toString
        workerParams.transportNetwork.transitLayer.services
        Option(svc.calendar) match {
          case Some(calendar) =>
            csvWriter.write(
              Vector(
                escape(svc.service_id),
                isWorking,
                escape(calendar.feed_id),
                calendar.monday.toString,
                calendar.tuesday.toString,
                calendar.wednesday.toString,
                calendar.thursday.toString,
                calendar.friday.toString,
                calendar.saturday.toString,
                calendar.sunday.toString,
                calendar.start_date.toString,
                calendar.end_date.toString
              ): _*
            )
          case None =>
            val row = Vector(escape(svc.service_id), isWorking) ++ Vector.fill(headers.size - 2)("")
            csvWriter.write(row: _*)
        }
    }
    csvWriter.close()
  }

  private def showDatesAndServices(workerParams: R5Parameters): Unit = {
    val nServices = workerParams.transportNetwork.transitLayer.services.size()
    val firstDate = LocalDate.of(2019, 1, 1)
    val lastDate = LocalDate.of(2020, 8, 1)
    val it: Iterator[LocalDate] = new Iterator[LocalDate] {
      var n: Int = 0

      override def hasNext: Boolean = firstDate.plusDays(n).isBefore(lastDate)

      override def next(): LocalDate = {
        val curr = firstDate.plusDays(n)
        n += 1
        curr
      }
    }

    val dateToActiveServices = it
      .map { date =>
        val activeServices = workerParams.transportNetwork.transitLayer.getActiveServicesForDate(date)
        val nActiveServices = (0 until nServices).count(idx => activeServices.get(idx))
        (date, nActiveServices)
      }
      .toList
      .sortBy { case (_, n) => n }(Ordering[Int].reverse)
    dateToActiveServices.foreach {
      case (d, n) =>
        println(s"$d => $n")
    }
  }

  def routeWithStreetRouter(
    r5Wrapper: R5Wrapper,
    streetLayer: StreetLayer,
    beamMode: BeamMode,
    startWgs: Coord,
    endWgs: Coord
  ): StreetRouter = {
    val r5Req = r5Wrapper.createProfileRequest
    r5Req.fromLon = startWgs.getX
    r5Req.fromLat = startWgs.getY
    r5Req.toLon = endWgs.getX
    r5Req.toLat = endWgs.getY

    val streetRouter = new StreetRouter(streetLayer)

    streetRouter.profileRequest = r5Req
    streetRouter.streetMode = toR5StreetMode(beamMode)
    streetRouter.timeLimitSeconds = r5Req.streetTime * 60
    require(streetRouter.setOrigin(r5Req.fromLat, r5Req.fromLon))
    require(streetRouter.setDestination(r5Req.toLat, r5Req.toLon))
    streetRouter.route()
    streetRouter
  }

}
