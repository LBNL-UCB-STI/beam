package beam.router.skim.urbansim

import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.{FreeFlowTravelTime, Modes, Router}
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.router.util.TravelTime

import java.io.File
import java.nio.file.Paths
import scala.language.postfixOps
import scala.reflect.io.Directory

case class ODRouterR5GHForActivitySimSkims(
  workerParams: R5Parameters,
  requestTimes: List[Int],
  travelTimeOpt: Option[TravelTime]
) extends Router
    with LazyLogging {

  var totalRouteExecutionInfo: RouteExecutionInfo = RouteExecutionInfo()

  private val r5: R5Wrapper = new R5Wrapper(
    workerParams,
    travelTimeOpt.getOrElse(new FreeFlowTravelTime),
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private val linksBelowMinCarSpeed =
    workerParams.networkHelper.allLinks
      .count(l => l.getFreespeed < workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    logger.warn(
      "{} links are below quick_fix_minCarSpeedInMetersPerSecond, already in free-flow",
      linksBelowMinCarSpeed
    )
  }

  val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
    .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
    .toMap

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString

  private val timeToCarGraphHopper: Map[Int, GraphHopperWrapper] =
    createCarGraphHoppers(carGraphHopperDir, requestTimes, travelTimeOpt)

  private val walkGraphHopper = createWalkGraphHopper()

  override def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    val carRequested = buildDirectCarRoute && request.streetVehicles.exists(_.mode == BeamMode.CAR)
    val walkRequested = buildDirectWalkRoute && request.streetVehicles.exists(_.mode == BeamMode.WALK)

    val (resultingResponse, executionInfo) = if (carRequested || walkRequested) {
      val carExecutionStart = System.nanoTime()
      val maybeCarGHRoute = if (carRequested) calcCarGhRouteWithoutTransit(request) else None
      val carExecutionDuration = System.nanoTime() - carExecutionStart

      val walkExecutionStart = System.nanoTime()
      val maybeWalkGHRoute = if (walkRequested) calcWalkGhRouteWithoutTransit(request) else None
      val walkExecutionDuration = System.nanoTime() - walkExecutionStart

      def routeIsEmpty(route: Option[RoutingResponse]): Boolean = {
        val routeExist = route.exists(_.itineraries.nonEmpty)
        !routeExist
      }

      val needToBuildCarRoute = routeIsEmpty(maybeCarGHRoute) && carRequested
      val needToBuildWalkRoute = routeIsEmpty(maybeWalkGHRoute) && walkRequested

      val otherVehiclesRequested =
        request.streetVehicles.exists(vehicle => vehicle.mode != BeamMode.CAR && vehicle.mode != BeamMode.WALK)
      val needToRunR5: Boolean = {
        needToBuildCarRoute ||
        needToBuildWalkRoute ||
        request.withTransit ||
        otherVehiclesRequested
      }

      val r5ExecutionStart = System.nanoTime()
      val maybeR5Response = if (needToRunR5) {
        Some(
          r5.calcRoute(
            request,
            buildDirectCarRoute = needToBuildCarRoute,
            buildDirectWalkRoute = needToBuildWalkRoute
          )
        )
      } else {
        None
      }
      val r5ExecutionTime = System.nanoTime() - r5ExecutionStart

      val response = maybeCarGHRoute
        .getOrElse(maybeWalkGHRoute.getOrElse(maybeR5Response.get))
        .copy(
          maybeCarGHRoute.map(_.itineraries).getOrElse(Seq.empty) ++
          maybeWalkGHRoute.map(_.itineraries).getOrElse(Seq.empty) ++
          maybeR5Response.map(_.itineraries).getOrElse(Seq.empty)
        )

      val executionInfo = RouteExecutionInfo(
        r5ExecutionTime = r5ExecutionTime,
        ghCarExecutionDuration = carExecutionDuration,
        ghWalkExecutionDuration = walkExecutionDuration,
        r5Responses = if (maybeR5Response.nonEmpty && maybeR5Response.get.itineraries.nonEmpty) 1 else 0,
        ghCarResponses = if (maybeCarGHRoute.nonEmpty && maybeCarGHRoute.get.itineraries.nonEmpty) 1 else 0,
        ghWalkResponses = if (maybeWalkGHRoute.nonEmpty && maybeWalkGHRoute.get.itineraries.nonEmpty) 1 else 0
      )

      (response, executionInfo)
    } else {
      val r5ExecutionStart = System.nanoTime()
      val response = r5.calcRoute(request, buildDirectCarRoute, buildDirectWalkRoute)
      val r5ExecutionTime = System.nanoTime() - r5ExecutionStart
      (response, RouteExecutionInfo(r5ExecutionTime = r5ExecutionTime, r5Responses = 1))
    }

    totalRouteExecutionInfo = RouteExecutionInfo.sum(totalRouteExecutionInfo, executionInfo)
    resultingResponse
  }

  private def createCarGraphHoppers(
    carGraphHopperDir: String,
    requestTimes: List[Int],
    travelTime: Option[TravelTime] = None
  ): Map[Int, GraphHopperWrapper] = {
    new Directory(new File(carGraphHopperDir)).deleteRecursively()
    val carRouter = "quasiDynamicGH"

    val timeToCarGraphHopper = requestTimes.map { time =>
      val ghDir = Paths.get(carGraphHopperDir, time.toString).toString

      val wayId2TravelTime = travelTime
        .map { times =>
          workerParams.networkHelper.allLinks.toSeq
            .map(
              l =>
                l.getId.toString.toLong ->
                times.getLinkTravelTime(l, time, null, null)
            )
            .toMap
        }
        .getOrElse(Map.empty)

      GraphHopperWrapper.createCarGraphDirectoryFromR5(
        carRouter,
        workerParams.transportNetwork,
        new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
        ghDir,
        wayId2TravelTime
      )

      time -> new CarGraphHopperWrapper(
        carRouter,
        ghDir,
        workerParams.geo,
        workerParams.vehicleTypes,
        workerParams.fuelTypePrices,
        wayId2TravelTime,
        id2Link
      )
    }.toMap

    logger.info(s"GH built")
    timeToCarGraphHopper
  }

  private def createWalkGraphHopper(): WalkGraphHopperWrapper = {
    GraphHopperWrapper.createWalkGraphDirectoryFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir
    )

    new WalkGraphHopperWrapper(graphHopperDir, workerParams.geo, id2Link)
  }

  private def calcCarGhRouteWithoutTransit(request: RoutingRequest): Option[RoutingResponse] = {
    val carRequest =
      request.copy(streetVehicles = request.streetVehicles.filter(_.mode == Modes.BeamMode.CAR), withTransit = false)

    if (carRequest.streetVehicles.nonEmpty) {
      timeToCarGraphHopper.get(carRequest.departureTime) match {
        case Some(carGraphHopper) => Some(carGraphHopper.calcRoute(carRequest))
        case None =>
          logger.error(
            s"Request departure time ${carRequest.departureTime} was not expected. GH route calculation cancelled."
          )
          None
      }
    } else {
      None
    }
  }

  private def calcWalkGhRouteWithoutTransit(request: RoutingRequest): Option[RoutingResponse] = {
    val walkRequest =
      request.copy(streetVehicles = request.streetVehicles.filter(_.mode == Modes.BeamMode.WALK), withTransit = false)

    if (walkRequest.streetVehicles.nonEmpty) {
      Some(walkGraphHopper.calcRoute(walkRequest))
    } else {
      None
    }
  }
}
