package beam.utils.map

import java.nio.charset.StandardCharsets
import java.util
import java.util.concurrent.TimeUnit

import beam.router.BeamRouter.RoutingRequest
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode.WALK_TRANSIT
import beam.router.R5Requester.prepareConfig
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.common.GeoUtils
import beam.utils.ParquetReader
import beam.utils.json.AllNeededFormats._
import com.conveyal.r5.api.util.{LegMode, TransitModes}
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import org.apache.avro.generic.GenericRecord
import org.apache.avro.util.Utf8

import scala.collection.JavaConverters._

object NewYorkRouteDebugging {
  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def walkWithOneItinerary(record: GenericRecord): Boolean = {
    val isWithinTransitTime = record.get("startPoint_time").asInstanceOf[Int] >= TimeUnit.HOURS.toSeconds(6) && record
      .get("startPoint_time")
      .asInstanceOf[Int] <= TimeUnit.HOURS.toSeconds(19)
    record.get("itineraries").asInstanceOf[Int] == 1 && record
      .get("tripClassifier")
      .toString == "walk" && isWithinTransitTime
  }

  def main(args: Array[String]): Unit = {
    val onlyWalkResponseRecords = {
      val (it, toClose) = ParquetReader.read(
        "C:/repos/beam/output/newyork/NYC-20k__2020-08-11_23-10-55_djq/ITERS/it.0/0.routingResponse.parquet"
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
        "C:/repos/beam/output/newyork/NYC-20k__2020-08-11_23-10-55_djq/ITERS/it.0/0.routingRequest.parquet"
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

    val runArgs = Array("--config", "test/input/newyork/new-york-20k-best-calibration-results.conf")
    val (_, cfg) = prepareConfig(runArgs, isConfigArgRequired = true)

    val workerParams: R5Parameters = R5Parameters.fromConfig(cfg)
    val r5Wrapper: R5Wrapper = new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)

    val ppQuery = new PointToPointQuery(workerParams.transportNetwork)

    var totalWalkTransitsByPointToPointQuery: Int = 0
    requests.take(100).foreach { req =>
      val resp = r5Wrapper.calcRoute(req)
      if (!resp.itineraries.exists(x => x.tripClassifier == WALK_TRANSIT)) {
        val startWgs = geoUtils.utm2Wgs(req.originUTM)
        val endWgs = geoUtils.utm2Wgs(req.destinationUTM)

        val r5Req = r5Wrapper.createProfileRequest
        r5Req.fromLon = startWgs.getX
        r5Req.fromLat = startWgs.getY
        r5Req.toLon = endWgs.getX
        r5Req.toLat = endWgs.getY
        r5Req.transitModes = util.EnumSet.allOf(classOf[TransitModes])
        r5Req.directModes = util.EnumSet.of(LegMode.WALK)
        r5Req.accessModes = util.EnumSet.of(LegMode.WALK)
        r5Req.egressModes = util.EnumSet.of(LegMode.WALK)
        r5Req.fromTime = req.departureTime
        r5Req.toTime = req.departureTime + 61

        val plan = ppQuery.getPlan(r5Req)
        val withTransits = plan.options.asScala.filter(x => Option(x.transit).map(_.size()).getOrElse(0) > 0)
        if (withTransits.nonEmpty) totalWalkTransitsByPointToPointQuery += 1

        println(s"Request ${req.requestId}")
        println(s"Plan size: ${plan.options.size()}, withTransits: ${withTransits.size}")

        println(s"Plan: ${plan}")
//        val link = s"http://localhost:8080/transit_plan?fromLat=${startWgs.getY}&fromLon=${startWgs.getX}&toLat=${endWgs.getY}&toLon=${endWgs.getX}&mode=WALK&full=false&departureTime=" + req.departureTime
//        println(s"Link to R5 PointToPointRouterServer: ${link}")
      }
    }
    println(s"totalWalkTransitsByPointToPointQuery: $totalWalkTransitsByPointToPointQuery")

  }

}
