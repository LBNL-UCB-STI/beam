package beam.router.skim.urbansim

import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.{FreeFlowTravelTime, Modes, Router}
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time

import java.io.File
import java.nio.file.Paths
import scala.language.postfixOps
import scala.reflect.io.Directory

case class ODRouter_ProofOfConcept(workerParams: R5Parameters, travelTimeOpt: Option[TravelTime])
    extends Router
    with LazyLogging {
  private val carRouter = workerParams.beamConfig.beam.routing.carRouter
  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
  private var binToCarGraphHopper: Map[Int, GraphHopperWrapper] = _
  private var walkGraphHopper: GraphHopperWrapper = _

  var totalRouteExecitionInfo: RouteExecutionInfo = RouteExecutionInfo()

  private val noOfTimeBins = Math
    .floor(
      Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
      workerParams.beamConfig.beam.agentsim.timeBinSize
    )
    .toInt

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

  // Let the dispatcher on which the Future in receive will be running
  // be the dispatcher on which this actor is running.
  val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
    .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
    .toMap

  if (carRouter == "staticGH" || carRouter == "quasiDynamicGH") {
    new Directory(new File(graphHopperDir)).deleteRecursively()
    createWalkGraphHopper()
    createCarGraphHoppers(travelTimeOpt)
  }

  override def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    val carRequested = request.streetVehicles.exists(_.mode == CAR)
    val walkRequested = request.streetVehicles.exists(_.mode == WALK)

    val (resultingResponse, executionInfo) =
      if ((carRequested || walkRequested) && (carRouter == "staticGH" || carRouter == "quasiDynamicGH")) {
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

        val needToBuildCarRoute = routeIsEmpty(maybeCarGHRoute)
        val needToBuildWalkRoute = routeIsEmpty(maybeWalkGHRoute)

        val otherVehiclesRequested =
          request.streetVehicles.exists(vehicle => vehicle.mode != CAR && vehicle.mode != WALK)
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
          .getOrElse(maybeWalkGHRoute.get)
          .copy(
            maybeCarGHRoute.map(_.itineraries).getOrElse(Seq.empty) ++
            maybeWalkGHRoute.map(_.itineraries).getOrElse(Seq.empty) ++
            maybeR5Response.map(_.itineraries).getOrElse(Seq.empty)
          )

        val executionInfo = RouteExecutionInfo(
          r5ExecutionTime,
          carExecutionDuration,
          walkExecutionDuration,
          r5Responses = if (maybeR5Response.nonEmpty && maybeR5Response.get.itineraries.nonEmpty) 1 else 0,
          ghCarResponses = if (maybeCarGHRoute.nonEmpty && maybeCarGHRoute.get.itineraries.nonEmpty) 1 else 0,
          ghWalkResponses = if (maybeWalkGHRoute.nonEmpty && maybeWalkGHRoute.get.itineraries.nonEmpty) 1 else 0
        )

        (response, executionInfo)
      } else {
        val r5ExecutionStart = System.nanoTime()
        val response = r5.calcRoute(request)
        val r5ExecutionTime = System.nanoTime() - r5ExecutionStart
        (response, RouteExecutionInfo(r5ExecutionTime = r5ExecutionTime, r5Responses = 1))
      }

    totalRouteExecitionInfo = RouteExecutionInfo.sum(totalRouteExecitionInfo, executionInfo)
    resultingResponse
  }

  private def createWalkGraphHopper(): Unit = {
    GraphHopperWrapper.createWalkGraphDirectoryFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir
    )

    walkGraphHopper = new WalkGraphHopperWrapper(graphHopperDir, workerParams.geo, id2Link)
  }

  private def createCarGraphHoppers(travelTime: Option[TravelTime] = None): Unit = {
    // Clean up GHs variable and than calculate new ones
    binToCarGraphHopper = Map()
    new Directory(new File(carGraphHopperDir)).deleteRecursively()

    val graphHopperInstances = if (carRouter == "quasiDynamicGH") noOfTimeBins else 1

    binToCarGraphHopper = (0 until graphHopperInstances).map { i =>
      val ghDir = Paths.get(carGraphHopperDir, i.toString).toString

      val wayId2TravelTime = travelTime
        .map { times =>
          workerParams.networkHelper.allLinks.toSeq
            .map(
              l =>
                l.getId.toString.toLong ->
                times.getLinkTravelTime(l, i * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null)
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

      i -> new CarGraphHopperWrapper(
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
  }

  private def calcCarGhRouteWithoutTransit(request: RoutingRequest): Option[RoutingResponse] = {
    val carRequest =
      request.copy(streetVehicles = request.streetVehicles.filter(_.mode == Modes.BeamMode.CAR), withTransit = false)

    if (carRequest.streetVehicles.nonEmpty) {
      val idx = if (carRouter == "quasiDynamicGH") {
        Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
      } else {
        0
      }
      Some(binToCarGraphHopper(idx).calcRoute(carRequest))
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
