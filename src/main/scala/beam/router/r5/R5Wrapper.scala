package beam.router.r5

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.freight.FreightEntities.FREIGHT_ID_PREFIX
import beam.agentsim.agents.ridehail.RideHailVehicleId.{getFleetName, isRideHail}
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.IntermodalUse._
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode._
import beam.router.Modes.{mapLegMode, toR5StreetMode, BeamMode}
import beam.router.RoutingWorker.{createBushwackingBeamLeg, R5Request, StopVisitor}
import beam.router.gtfs.FareCalculator.{filterFaresOnTransfers, BeamFareSegment}
import beam.router.model.BeamLeg.dummyLeg
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model._
import beam.router.r5.BikeLanesAdjustment.bikeLanesAdjustment
import beam.router.skim.SkimsUtils.{getRideHailCost, getRideHailManagerCosts}
import beam.router.{Modes, Router, RoutingWorker}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils.MeasureUnitConversion.METERS_IN_MILE
import com.conveyal.r5.analyst.fare.{InRoutingFareCalculator, SimpleInRoutingFareCalculator}
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.{TransitLayer, TransportNetwork}
import com.typesafe.scalalogging.StrictLogging
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.{TLongByteHashMap, TLongObjectHashMap}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util
import java.util.concurrent.atomic.AtomicLong
import java.util.function.IntFunction
import java.util.{Collections, Optional}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait TravelTimeByLinkCalculator {
  def apply(time: Double, linkId: Int, streetMode: StreetMode): Double
}

/**
  * Stateless travel time calculator that can be cached and reused across requests.
  * Instead of capturing the search start time in a closure, it accepts it as a parameter.
  */
@SuppressWarnings(Array("UnusedMethodParameter"))
private class BeamTravelTimeCalculator(
  vehicleType: BeamVehicleType,
  shouldAddNoise: Boolean,
  shouldApplyBicycleScaleFactor: Boolean,
  travelTimeByLinkCalc: TravelTimeByLinkCalculator
) extends TravelTimeCalculator {

  override def getTravelTimeSeconds(
    edge: EdgeStore#Edge,
    durationSeconds: Int,
    streetMode: StreetMode,
    req: ProfileRequest
  ): Float = {
    // Delegate to the 5-param version using req.fromTime
    getTravelTimeSeconds(edge, durationSeconds, streetMode, req, req.fromTime)
  }

  override def getTravelTimeSeconds(
    edge: EdgeStore#Edge,
    durationSeconds: Int,
    streetMode: StreetMode,
    req: ProfileRequest,
    searchStartTimeSeconds: Int // ← Uses this instead of captured value!
  ): Float = {
    // Calculate absolute time using the provided start time
    val absoluteTime = searchStartTimeSeconds + durationSeconds
    math.ceil(travelTimeByLinkCalc(absoluteTime, edge.getEdgeIndex, streetMode).toFloat).toFloat
  }
}

/**
  * R5Wrapper is a BEAM wrapper for the R5 routing engine.
  *
  * This class is responsible for calculating routes for various modes of transport, including street-based modes (walk, car, bike) and transit.
  * It has been heavily optimized for performance, primarily through the use of thread-local object pooling for expensive R5 components
  * like `StreetRouter` and `McRaptorSuboptimalPathProfileRouter`. This strategy significantly reduces object churn and garbage collection
  * pressure in a highly concurrent environment.
  *
  * Key Features:
  * - **Multi-modal Routing**: Supports CAR, BIKE, WALK, and TRANSIT modes, along with intermodal trips (e.g., drive-to-transit).
  * - **Router Pooling**: Manages thread-local pools of `StreetRouter` and `McRaptor` instances to avoid costly re-initialization.
  * - **State Pooling**: Utilizes R5's `StatePool` and `McRaptorStatePool` to recycle state objects during routing searches.
  * - **Caching**: Caches `TravelTimeCalculator` instances and street transfer segments to reduce redundant computations.
  * - **Performance-aware Logging**: Designates a single thread for logging detailed statistics to minimize logging overhead on worker threads.
  * - **Request Filtering**: Includes logic to filter access, egress, and direct modes based on the predetermined mode choice, which
  *   prunes the search space and reduces unnecessary routing calculations.
  *
  * @param workerParams Parameters for the R5 worker, including the transport network and BEAM configuration.
  * @param travelTime A MATSim `TravelTime` instance for calculating link travel times.
  * @param travelTimeNoiseFraction Fraction of noise to add to travel times.
  */
class R5Wrapper(workerParams: R5Parameters, travelTime: TravelTime, travelTimeNoiseFraction: Double)
    extends MetricsSupport
    with StrictLogging
    with Router {
  import R5Wrapper._

  private val maxDistanceForBikeMeters: Int =
    workerParams.beamConfig.beam.routing.r5.maxDistanceLimitByModeInMeters.bike

  private val useMcRaptorRouterPooling: Boolean =
    workerParams.beamConfig.beam.routing.r5.useMcRaptorRouterPooling

  private val R5Parameters(
    beamConfig,
    transportNetwork,
    vehicleTypes,
    fuelTypePrices,
    ptFares,
    geo,
    dates,
    networkHelper,
    fareCalculator,
    tollCalculator
  ) = workerParams

  private val logPoolPressureWarnings: Boolean = beamConfig.beam.routing.r5.logPoolPressureWarnings
  private val streetRouterPoolWarnUtilizationThreshold = 0.9

  private def mcRaptorListSupplierType(isDriveTransitRequest: Boolean): String = {
    if (isDriveTransitRequest) {
      "beam"
    } else {
      beamConfig.beam.routing.r5.transitAlternativeList.toLowerCase match {
        case "suboptimal" => "suboptimal"
        case _            => "beam"
      }
    }
  }

  private def handleMcRaptorGetPathsFailure(
    mode: LegMode,
    error: Throwable
  ): Unit = {
    logger.error(s"[MCRAPTOR-EXCEPTION] mode=$mode", error)
  }

  private lazy val walkVehicleTypeId: Id[BeamVehicleType] = Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType])

  private lazy val osmIdToRoadRestriction: Map[Long, RoadRestrictions] = networkHelper.allLinks.flatMap { link =>
    for {
      osmIdStr <- Option(link.getAttributes.getAttribute("origid"))
      osmId    <- Try(osmIdStr.toString.toLong).toOption
    } yield osmId -> RoadRestrictions(
      Option(link.getAttributes.getAttribute(HeavyHeavyDutyTruckTag))
        .flatMap(attr => Try(attr.toString.toBoolean).toOption)
        .getOrElse(false),
      Option(link.getAttributes.getAttribute(LightAndMediumHeavyDutyTruckTag))
        .flatMap(attr => Try(attr.toString.toBoolean).toOption)
        .getOrElse(false),
      Try(link.getFreespeed).getOrElse(0.0)
    )
  }.toMap

  private lazy val precomputedRestrictions: Map[RoutingVehicleCategory, TLongByteHashMap] = {
    val categories = RoutingVehicleCategory.values
    categories.map { category =>
      val categoryRestrictions = new TLongByteHashMap()
      osmIdToRoadRestriction.foreach { case (osmId, restrictions) =>
        val isRestricted = restrictions.isRestricted(
          category,
          Double.MaxValue
        )
        categoryRestrictions.put(osmId, if (isRestricted) 1.toByte else 0.toByte)
      }
      category -> categoryRestrictions
    }.toMap
  }

  private lazy val osmIdToRoadRestrictionTrove: TLongObjectHashMap[RoadRestrictions] = {
    val troveMap = new TLongObjectHashMap[RoadRestrictions](osmIdToRoadRestriction.size)
    osmIdToRoadRestriction.foreach { case (osmId, restriction) =>
      troveMap.put(osmId, restriction)
    }
    troveMap
  }

  private val linkRadiusMeters: Double =
    beamConfig.beam.routing.r5.linkRadiusMeters

  private val statePoolSize: Int = beamConfig.beam.routing.r5.statePoolSize.primary

  private def accessEgressStatePoolSize(mode: StreetMode): Int = {
    mode match {
      case StreetMode.CAR     => beamConfig.beam.routing.r5.statePoolSize.car
      case StreetMode.WALK    => beamConfig.beam.routing.r5.statePoolSize.walk
      case StreetMode.BICYCLE => beamConfig.beam.routing.r5.statePoolSize.bike
    }
  }

  private val carWeightCalculator = new CarWeightCalculator(workerParams, travelTimeNoiseFraction)
  private val bikeScaleFactor = bikeLanesAdjustment(beamConfig)

  // Create thread-local for each calculator type
  private val turnCostCalculatorTL: ThreadLocal[TurnCostCalculator] =
    ThreadLocal.withInitial(() =>
      new TurnCostCalculator(transportNetwork.streetLayer, true) {
        override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
      }
    )

  private case class RoutingVehicleConfig(
    streetMode: StreetMode,
    maxSpeedMps: Option[Double],
    shouldAddNoise: Boolean,
    shouldApplyBicycleScaleFactor: Boolean
  )

  private def getRoutingConfig(
    vehicleType: BeamVehicleType,
    shouldAddNoise: Boolean,
    shouldApplyBicycleScaleFactor: Boolean
  ): RoutingVehicleConfig = {
    val streetMode = Modes.toR5StreetMode(vehicleType.vehicleCategory match {
      case VehicleCategory.Car  => BeamMode.CAR
      case VehicleCategory.Bike => BeamMode.BIKE
      case _                    => BeamMode.WALK
    })

    RoutingVehicleConfig(
      streetMode,
      vehicleType.maxVelocity, // Only matters for cars
      shouldAddNoise,
      shouldApplyBicycleScaleFactor
    )
  }

  private val travelTimeCalculatorCache: ThreadLocal[mutable.Map[
    RoutingVehicleConfig,
    BeamTravelTimeCalculator
  ]] = ThreadLocal.withInitial(() => mutable.Map.empty)

  private case class RouterCacheKey(statePool: StatePool, quantityToMinimize: StreetRouter.State.RoutingVariable)

  private case class McRaptorRouterCacheKey(
    streetMode: StreetMode,
    listSupplierType: String
  )

  private val routerCaches: ThreadLocal[mutable.Map[RouterCacheKey, util.ArrayDeque[StreetRouter]]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[RouterCacheKey, util.ArrayDeque[StreetRouter]])

  private def getMcRaptorPoolSize(streetMode: StreetMode, listSupplierType: String): Int = {
    (streetMode, listSupplierType) match {
      case (StreetMode.WALK, "suboptimal")    => beamConfig.beam.routing.r5.statePoolSize.walk_transit_suboptimal
      case (StreetMode.CAR, "suboptimal")     => beamConfig.beam.routing.r5.statePoolSize.drive_transit_suboptimal
      case (StreetMode.BICYCLE, "suboptimal") => beamConfig.beam.routing.r5.statePoolSize.bike_transit_suboptimal
      case (StreetMode.WALK, "beam")          => beamConfig.beam.routing.r5.statePoolSize.walk_transit_optimal
      case (StreetMode.CAR, "beam")           => beamConfig.beam.routing.r5.statePoolSize.drive_transit_optimal
      case (StreetMode.BICYCLE, "beam")       => beamConfig.beam.routing.r5.statePoolSize.bike_transit_optimal
      case _                                  => 100000
    }
  }

  private val transferSegmentCache = TrieMap.empty[(Int, Int), StreetSegment]

  private val mcRaptorStatePools: ThreadLocal[mutable.Map[McRaptorRouterCacheKey, McRaptorStatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[McRaptorRouterCacheKey, McRaptorStatePool])

  private val mcRaptorRouterCaches
    : ThreadLocal[mutable.Map[McRaptorRouterCacheKey, util.ArrayDeque[McRaptorSuboptimalPathProfileRouter]]] =
    ThreadLocal.withInitial(() =>
      mutable.Map.empty[McRaptorRouterCacheKey, util.ArrayDeque[McRaptorSuboptimalPathProfileRouter]]
    )

  /**
    * Borrows a `McRaptorSuboptimalPathProfileRouter` from a thread-local pool.
    *
    * This method manages a pool of McRaptor routers to avoid the high cost of their initialization.
    * It uses a `McRaptorRouterCacheKey` (based on street mode and list supplier type) to segregate
    * routers in different caches. If a cached router is available, it's reused; otherwise, a new one is created.
    * This method also manages the underlying `McRaptorStatePool`.
    *
    * @param streetMode The access/egress mode (e.g., WALK, CAR).
    * @param profileRequest The R5 profile request.
    * @param accessTimes A map of leg modes to their access times to transit stops.
    * @param egressTimes A map of leg modes to their egress times from transit stops.
    * @param departureTimeToDominatingList A function to get the dominating list for a given departure time.
    * @param collapseParetoSurfaceToTime A collater for fare calculations.
    * @param isDriveTransitRequest A flag indicating if this is a drive-to-transit request.
    * @return A reset and ready-to-use `McRaptorSuboptimalPathProfileRouter`.
    */
  private def borrowMcRaptorRouter(
    streetMode: StreetMode,
    profileRequest: ProfileRequest,
    accessTimes: java.util.Map[LegMode, gnu.trove.map.TIntIntMap],
    egressTimes: java.util.Map[LegMode, gnu.trove.map.TIntIntMap],
    departureTimeToDominatingList: IntFunction[DominatingList],
    collapseParetoSurfaceToTime: InRoutingFareCalculator.Collater,
    isDriveTransitRequest: Boolean
  ): McRaptorSuboptimalPathProfileRouter = {

    val listSupplierType = mcRaptorListSupplierType(isDriveTransitRequest)

    val cacheKey = McRaptorRouterCacheKey(streetMode, listSupplierType)

    // Get or create state pool specific to both mode and list supplier type.
    // WALK/beam and WALK/suboptimal should not share a pool because their sizing can be very different.
    val poolSize = getMcRaptorPoolSize(streetMode, listSupplierType)
    val statePool = mcRaptorStatePools
      .get()
      .getOrElseUpdate(
        cacheKey, {
          new McRaptorStatePool(poolSize)
        }
      )

    // Get or create router cache
    val routerCache =
      mcRaptorRouterCaches.get().getOrElseUpdate(cacheKey, new util.ArrayDeque[McRaptorSuboptimalPathProfileRouter](5))

    val router = if (routerCache.isEmpty) {
      // Create new router with the state pool
      val newRouter = new McRaptorSuboptimalPathProfileRouter(
        transportNetwork,
        profileRequest,
        accessTimes,
        egressTimes,
        departureTimeToDominatingList,
        collapseParetoSurfaceToTime,
        statePool
      )
      newRouter
    } else {
      routerCache.poll()
    }

    // Reset the router for reuse
    router.reset(profileRequest, accessTimes, egressTimes, departureTimeToDominatingList, collapseParetoSurfaceToTime)

    router
  }

  /**
    * Returns a `McRaptorSuboptimalPathProfileRouter` to the pool.
    *
    * After a router is used, this method should be called to return it to the thread-local cache for future reuse.
    * It also records statistics about the router's usage, including state pool consumption and any exhaustions.
    *
    * @param router The router to return.
    * @param r5mode The street mode the router was used for.
    * @param isDriveTransitRequest A flag indicating if it was a drive-to-transit request.
    */
  private def returnMcRaptorRouter(
    router: McRaptorSuboptimalPathProfileRouter,
    r5mode: StreetMode,
    isDriveTransitRequest: Boolean
  ): (Int, Int, Int) = {
    val listSupplierType = mcRaptorListSupplierType(isDriveTransitRequest)

    val cacheKey = McRaptorRouterCacheKey(r5mode, listSupplierType)
    val routerCache =
      mcRaptorRouterCaches.get().getOrElseUpdate(cacheKey, new util.ArrayDeque[McRaptorSuboptimalPathProfileRouter](5))

    // Track state pool usage
    val exhaustions = router.getStatePoolExhaustionsSinceReset
    val maxInUse = router.getStatePoolMaxInUse
    val poolSize = router.getStatePool.getPoolSize

    // Return router to cache
    if (routerCache.size() < 5) {
      routerCache.offer(router)
    }

    (exhaustions, maxInUse, poolSize)
  }

  private def borrowRouterWithStatePool(
    travelTimeCalculator: TravelTimeCalculator,
    travelCostCalculator: TravelCostCalculator,
    statePool: StatePool,
    quantityToMinimize: StreetRouter.State.RoutingVariable // NEW parameter
  ): StreetRouter = {

    val cacheKey = RouterCacheKey(statePool, quantityToMinimize)
    val cacheMap = routerCaches.get()
    val cache = cacheMap.getOrElseUpdate(cacheKey, new util.ArrayDeque[StreetRouter](50))

    val router = if (cache.isEmpty) {
      val newRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator,
        turnCostCalculatorTL.get(),
        travelCostCalculator,
        statePool,
        quantityToMinimize
      )
      newRouter
    } else {
      cache.poll()
    }

    router.reset(travelTimeCalculator, turnCostCalculatorTL.get(), travelCostCalculator)
    // quantityToMinimize is already correct for this cache
    router
  }

  private def returnRouterWithStatePool(
    router: StreetRouter,
    statePool: StatePool,
    quantityToMinimize: StreetRouter.State.RoutingVariable
  ): Unit = {
    maybeLogStreetRouterPoolPressure(router, quantityToMinimize)
    val cacheKey = RouterCacheKey(statePool, quantityToMinimize)
    val cacheMap = routerCaches.get()
    val cache = cacheMap.getOrElseUpdate(cacheKey, new util.ArrayDeque[StreetRouter](50))

    // Return router to cache
    if (cache.size() < 50) {
      cache.offer(router)
    }
  }

  private def maybeLogStreetRouterPoolPressure(
    router: StreetRouter,
    quantityToMinimize: StreetRouter.State.RoutingVariable
  ): Unit = {
    if (!logPoolPressureWarnings) return
    val statePoolExhaustions = router.getStatePoolExhaustionsSinceReset
    val statePoolMaxInUse = router.getStatePoolMaxInUse
    val statePoolSize = router.getStatePoolSize
    val statePoolUtilization =
      if (statePoolSize > 0) statePoolMaxInUse.toDouble / statePoolSize.toDouble else 0.0
    if (statePoolExhaustions > 0 || statePoolUtilization >= streetRouterPoolWarnUtilizationThreshold) {
      val requestFromTime = Option(router.profileRequest).map(_.fromTime).getOrElse(-1)
      logger.warn(
        s"[STREET-POOL-PRESSURE] mode=${router.streetMode}, qtm=$quantityToMinimize, fromTime=$requestFromTime, " +
        s"statePoolExhaustions=$statePoolExhaustions, statePoolMaxInUse=$statePoolMaxInUse, " +
        s"statePoolSize=$statePoolSize, " +
        f"statePoolUtilization=${statePoolUtilization * 100.0}%.1f%%, routerId=${System.identityHashCode(router)}"
      )
    }
  }

  // Each thread has its own set of state pools for transit access, keyed by street mode.
  private val transitAccessStatePools: ThreadLocal[mutable.Map[StreetMode, StatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[StreetMode, StatePool])

  // Each thread has its own set of state pools for transit egress, keyed by street mode.
  private val transitEgressStatePools: ThreadLocal[mutable.Map[StreetMode, StatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[StreetMode, StatePool])

  private def returnRouter(router: StreetRouter): Unit = {
    // Delegate to the unified method with mainRoutingPool
    returnRouterWithStatePool(router, mainRoutingPool.get(), StreetRouter.State.RoutingVariable.WEIGHT)
  }

  private val mainRoutingPool: ThreadLocal[StatePool] =
    ThreadLocal.withInitial(() => new StatePool(statePoolSize))

  private def borrowRouter(
    travelTimeCalc: TravelTimeCalculator,
    travelCostCalc: TravelCostCalculator
  ): StreetRouter = {
    borrowRouterWithStatePool(
      travelTimeCalc,
      travelCostCalc,
      mainRoutingPool.get(), // ← Use the 100k pool!
      StreetRouter.State.RoutingVariable.WEIGHT
    )
  }

  private def withRouter[T](
    travelTimeCalc: TravelTimeCalculator,
    travelCostCalc: TravelCostCalculator
  )(f: StreetRouter => T): T = {
    val router = borrowRouter(travelTimeCalc, travelCostCalc)
    try {
      f(router)
    } finally {
      returnRouter(router)
    }
  }

  /**
    * Updates the travel time of a leg with the current network travel times.
    *
    * This method is used to "embody" a previously planned leg with up-to-date travel times from the simulation.
    * It re-calculates the duration and cost of the leg based on the provided travel time calculator.
    *
    * @param leg The `BeamLeg` to update.
    * @param vehicleId The ID of the vehicle traversing the leg.
    * @param vehicleTypeId The type ID of the vehicle.
    * @param embodyRequestId A unique ID for this embodiment request.
    * @param triggerId The simulation trigger ID that initiated this request.
    * @return A `RoutingResponse` containing a single `EmbodiedBeamTrip` with the updated leg.
    */
  def embodyWithCurrentTravelTime(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    embodyRequestId: Int,
    triggerId: Long
  ): RoutingResponse = {
    val s = System.currentTimeMillis()
    val vehicleType = vehicleTypes(vehicleTypeId)
    val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
      leg.travelPath.linkIds,
      leg.startTime,
      travelTimeByLinkCalculator(vehicleType, shouldAddNoise = false),
      toR5StreetMode(leg.mode),
      transportNetwork.streetLayer
    )

    // .head and .last on Array[Int] are fine - no boxing
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val startLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.head)
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val endLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.last)

    // Manual sum to avoid boxing from .tail.sum
    var duration = 0.0
    var i = 1 // Start at 1 to skip first element (equivalent to .tail)
    while (i < linksTimesAndDistances.travelTimes.length) {
      duration += linksTimesAndDistances.travelTimes(i)
      i += 1
    }

    // Manual sum for distances (skip first element)
    var totalDistance = 0.0
    var j = 1
    while (j < linksTimesAndDistances.distances.length) {
      totalDistance += linksTimesAndDistances.distances(j)
      j += 1
    }

    val updatedTravelPath = BeamPath(
      linksTimesAndDistances.linkIds, // Already Array[Int] - no .toArray needed
      linksTimesAndDistances.travelTimes, // Already Array[Double] - no .toArray needed
      None,
      SpaceTime(startLoc.getX, startLoc.getY, leg.startTime),
      SpaceTime(
        endLoc.getX,
        endLoc.getY,
        leg.startTime + Math.round(duration.toFloat)
      ),
      distanceInM = totalDistance
    )

    val toll = tollCalculator.calcTollByLinkIds(updatedTravelPath)
    val updatedLeg = leg.copy(travelPath = updatedTravelPath, duration = updatedTravelPath.duration)
    val drivingCost = DrivingCost.estimateDrivingCost(
      updatedLeg.travelPath.distanceInM,
      updatedLeg.duration,
      vehicleType,
      fuelTypePrices.getOrElse(vehicleType.primaryFuelType, 0.0)
    )
    val totalCost = drivingCost + (if (updatedLeg.mode == BeamMode.CAR) toll else 0)

    val response = RoutingResponse(
      Vector(
        EmbodiedBeamTrip(
          Vector(
            EmbodiedBeamLeg(
              updatedLeg,
              vehicleId,
              vehicleTypeId,
              asDriver = true,
              totalCost,
              unbecomeDriverOnCompletion = true
            )
          ),
          Some("R5")
        )
      ),
      embodyRequestId,
      None,
      isEmbodyWithCurrentTravelTime = true,
      System.currentTimeMillis() - s,
      searchedModes = Set.empty,
      triggerId
    )
    response
  }

  /**
    * Calculates a street-only route (no transit) between two points.
    *
    * This method is used for direct, non-transit trips (e.g., CAR, WALK, BIKE).
    * It uses a pooled `StreetRouter` to perform the calculation.
    *
    * @param request The R5 request, containing origin, destination, time, and vehicle information.
    * @param requestedMode An optional `BeamMode` to filter the modes to be routed.
    * @return A `ProfileResponse` containing the direct street-based route options.
    */
  private def getStreetPlanFromR5(request: R5Request, requestedMode: Option[BeamMode]): ProfileResponse = {
    val streetRoutingStarted = System.currentTimeMillis()
    countOccurrence("r5-plans-count", request.time)
    val vehicleType = vehicleTypes(request.beamVehicleTypeId)
    val profileRequest = createProfileRequestFromRequest(request)
    try {
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      val directModesToRoute = filterDirectModesForPredeterminedMode(
        profileRequest.directModes.asScala.toSeq,
        requestedMode,
        request.withTransit
      )
      if (requestedMode.contains(FREIGHT)) {
        profileRequest.maxTripDurationMinutes = 4 * 60
      } // Let freight trips be longer
      for (mode <- directModesToRoute) {
        withRouter(
          getTravelTimeCalculator(vehicleType, shouldAddNoise = !profileRequest.hasTransit),
          travelCostCalculator(
            vehicleType,
            request.timeValueOfMoney,
            profileRequest.fromTime
          )
        ) { streetRouter =>
          if (request.accessMode == LegMode.BICYCLE) {
            streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
          }
          streetRouter.profileRequest = profileRequest
          streetRouter.streetMode = toR5StreetMode(mode)
          streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
          if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon, linkRadiusMeters)) {
            if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
              latency("route-transit-time", Metrics.VerboseLevel) {
                streetRouter.route() // latency 1
              }
              val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
              if (lastState != null) {
                val streetPath = new StreetPath(lastState, transportNetwork, false)
                val streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer)
                directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
              }
            }
          }
        }
      }
      directOption.summary = directOption.generateSummary
      profileResponse.addOption(directOption)
      try {
        profileResponse.recomputeStats(profileRequest)
      } catch {
        case e: IllegalStateException if e.getMessage != null && e.getMessage.contains("No valid itineraries") =>
          logger.debug(s"No transit paths found, returning ${profileResponse.getOptions.size} direct options (if any)")
      }

      profileResponse
    } catch {
      case _: IllegalStateException =>
        new ProfileResponse
      case _: ArrayIndexOutOfBoundsException =>
        new ProfileResponse
    }
  }

  private def createProfileRequestFromRequest(request: R5Request) = {
    val result = createProfileRequest
    result.fromLon = request.from.getX
    result.fromLat = request.from.getY
    result.toLon = request.to.getX
    result.toLat = request.to.getY
    result.fromTime = request.time
    result.toTime = request.time + 61 // Important to allow 61 seconds for transit schedules to be considered!
    result.directModes = if (request.directMode == null) {
      util.EnumSet.noneOf(classOf[LegMode])
    } else {
      util.EnumSet.of(request.directMode)
    }
    result
  }

  def createProfileRequest: ProfileRequest = {
    val profileRequest = new ProfileRequest()
    // Warning: carSpeed is not used for link traversal (rather, the OSM travel time model is used),
    // but for R5-internal bushwhacking from network to coordinate, AND ALSO for the A* remaining weight heuristic,
    // which means that this value must be an over(!)estimation, otherwise we will miss optimal routes,
    // particularly in the presence of tolls.
    profileRequest.carSpeed = carWeightCalculator.maxFreeSpeed.toFloat
    profileRequest.maxWalkTime = 60
    profileRequest.maxCarTime = 30
    profileRequest.maxBikeTime = 30
    // Maximum number of transit segments. This was previously hardcoded as 4 in R5, now it is a parameter
    // that defaults to 8 unless I reset it here. It is directly related to the amount of work the
    // transit router has to do.
    profileRequest.maxRides = 3
    profileRequest.streetTime = 2 * 60
    profileRequest.maxTripDurationMinutes = 2 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    // BEAM uses deterministic routing to match scheduled vehicle departure times.
    profileRequest.monteCarloDraws = 0
    profileRequest.date = dates.localBaseDate
    // Doesn't calculate any fares, is just a no-op placeholder
    profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator
    profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutes
    profileRequest
  }

  /**
    * Calculates a multi-modal route based on a `RoutingRequest`.
    *
    * This is the main entry point for routing in BEAM. It handles complex routing scenarios, including:
    * - Direct trips (car, walk, bike)
    * - Transit trips
    * - Intermodal trips (e.g., driving to a transit station, taking transit, then walking to the destination)
    * - Trips involving personal or shared vehicles (e.g., ride-hail, car-sharing)
    *
    * The method leverages the router pooling and caching mechanisms to perform these calculations efficiently.
    * It constructs a `ProfileRequest` for R5 and interprets the `ProfileResponse` to generate BEAM-compatible `EmbodiedBeamTrip`s.
    *
    * @param request The `RoutingRequest` from a BEAM agent.
    * @param buildDirectCarRoute Whether to calculate a direct car route as an alternative.
    * @param buildDirectWalkRoute Whether to calculate a direct walk route as an alternative.
    * @return A `RoutingResponse` containing a list of possible itineraries (as `EmbodiedBeamTrip`s).
    */
  def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    val routeCalcStarted = System.currentTimeMillis()
    val accessRoutersToReturn = mutable.ArrayBuffer[(StreetRouter, StatePool, StreetRouter.State.RoutingVariable)]()
    val egressRoutersToReturn = mutable.ArrayBuffer[(StreetRouter, StatePool, StreetRouter.State.RoutingVariable)]()
    val driveTransitDiagnostics = new DriveTransitRequestDiagnostics(
      enableDriveTransitFailureDiagnostics && request.withTransit && request.requestedMode.contains(DRIVE_TRANSIT)
    )

    try {
      // For each street vehicle (including body, if available): Route from origin to street vehicle, from street vehicle to destination.
      val isRouteForPerson = request.streetVehicles.exists(_.mode == WALK)

      def calcRouteToVehicle(vehicle: StreetVehicle): Option[EmbodiedBeamLeg] = {
        val mainRouteFromVehicle =
          request.streetVehiclesUseIntermodalUse == Access && isRouteForPerson && vehicle.mode != WALK
        if (mainRouteFromVehicle) {
          val body = request.streetVehicles.find(_.mode == WALK).get
          if (
            geo.distUTMInMeters(
              vehicle.locationUTM.loc,
              request.originUTM
            ) > beamConfig.beam.agentsim.thresholdForWalkingInMeters
          ) {
            val fromWgs = geo.snapToR5Edge(
              transportNetwork.streetLayer,
              geo.utm2Wgs(request.originUTM),
              linkRadiusMeters
            )
            val toWgs = geo.snapToR5Edge(
              transportNetwork.streetLayer,
              geo.utm2Wgs(vehicle.locationUTM.loc),
              linkRadiusMeters
            )
            val directMode = LegMode.WALK
            val accessMode = LegMode.WALK
            val egressMode = LegMode.WALK

            val profileResponse =
              latency("walkToVehicleRoute-router-time", Metrics.RegularLevel) {
                getStreetPlanFromR5(
                  R5Request(
                    fromWgs,
                    toWgs,
                    request.departureTime,
                    directMode,
                    accessMode,
                    withTransit = false,
                    egressMode,
                    request.timeValueOfMoney,
                    body.vehicleTypeId
                  ),
                  requestedMode = if (request.personId.exists(_.toString.startsWith(FREIGHT_ID_PREFIX))) {
                    Some(FREIGHT)
                  } else { request.requestedMode }
                )
              }
            if (profileResponse.options.isEmpty) {
              Some(
                EmbodiedBeamLeg(
                  createBushwackingBeamLeg(request.departureTime, request.originUTM, vehicle.locationUTM.loc, geo),
                  body.id,
                  body.vehicleTypeId,
                  asDriver = true,
                  0,
                  unbecomeDriverOnCompletion = false
                )
              )
            } else {
              val streetSegment = profileResponse.options.get(0).access.get(0)
              Some(
                buildStreetBasedLegs(
                  streetSegment,
                  request.departureTime,
                  body,
                  unbecomeDriverOnCompletion = false
                )
              )
            }
          } else {
            Some(
              EmbodiedBeamLeg(
                dummyLeg(request.departureTime, geo.utm2Wgs(vehicle.locationUTM.loc)),
                body.id,
                body.vehicleTypeId,
                body.asDriver,
                0.0,
                unbecomeDriverOnCompletion = false
              )
            )
          }
        } else {
          None
        }
      }

      def routeFromVehicleToDestination(vehicle: StreetVehicle) = {
        // assume 13 mph / 5.8 m/s as average PT speed: http://cityobservatory.org/urban-buses-are-slowing-down/
        val estimateDurationToGetToVeh: Int = math
          .round(geo.distUTMInMeters(request.originUTM, vehicle.locationUTM.loc) / 5.8)
          .intValue()
        val time = request.departureTime + estimateDurationToGetToVeh
        val linkRadiusMeters = beamConfig.beam.routing.r5.linkRadiusMeters
        val fromWgs = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(vehicle.locationUTM.loc),
          linkRadiusMeters
        )
        val toWgs = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(request.destinationUTM),
          linkRadiusMeters
        )
        val vehicleLegMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
        val profileResponse =
          latency("vehicleOnEgressRoute-router-time", Metrics.RegularLevel) {
            getStreetPlanFromR5(
              R5Request(
                fromWgs,
                toWgs,
                time,
                directMode = vehicleLegMode,
                accessMode = vehicleLegMode,
                withTransit = false,
                egressMode = LegMode.WALK,
                request.timeValueOfMoney,
                vehicle.vehicleTypeId
              ),
              requestedMode = request.requestedMode match {
                case Some(DRIVE_TRANSIT) => Some(CAR)
                case Some(BIKE_TRANSIT)  => Some(BIKE)
                case _                   => None
              }
            )
          }
        if (!profileResponse.options.isEmpty) {
          val streetSegment = profileResponse.options.get(0).access.get(0)
          buildStreetBasedLegs(
            streetSegment,
            time,
            vehicle,
            unbecomeDriverOnCompletion = true
          )
        } else {
          EmbodiedBeamLeg(
            createBushwackingBeamLeg(request.departureTime, vehicle.locationUTM.loc, request.destinationUTM, geo),
            vehicle.id,
            vehicle.vehicleTypeId,
            asDriver = true,
            0,
            unbecomeDriverOnCompletion = true
          )
        }
      }

      /*
       * Our algorithm captures a few different patterns of travel. Two of these require extra routing beyond what we
       * call the "main" route calculation below. In both cases, we have a single main transit route
       * which is only calculate once in the code below. But we optionally add a WALK leg from the origin to the
       * beginning of the route (called "mainRouteFromVehicle" as opposed to main route from origin). Or we optionally
       * add a vehicle-based trip on the egress portion of the trip (called "mainRouteToVehicle" as opposed to main route
       * to destination).
       *
       * Or we use the R5 egress concept to accomplish "mainRouteRideHailTransit" pattern we use DRIVE mode on both
       * access and egress legs. For the other "mainRoute" patterns, we want to fix the location of the vehicle, not
       * make it dynamic. Also note that in all cases, these patterns are only the result of human travelers, we assume
       * AI is fixed to a vehicle and therefore only needs the simplest of routes.
       *
       * For the mainRouteFromVehicle pattern, the traveler is using a vehicle within the context of a
       * trip that could be multimodal (e.g. drive to transit) or unimodal (drive only). We don't assume the vehicle is
       * co-located with the person, so this first block of code determines the distance from the vehicle to the person and based
       * on a threshold, optionally routes a WALK leg to the vehicle and adjusts the main route location & time accordingly.
       *
       */
      val mainRouteToVehicle = request.streetVehiclesUseIntermodalUse == Egress && isRouteForPerson
      val mainRouteRideHailTransit =
        Set(AccessAndEgress, AccessAndOrEgress).contains(request.streetVehiclesUseIntermodalUse) && isRouteForPerson

      val profileRequest = createProfileRequest
      val accessVehicles = if (mainRouteToVehicle) {
        Vector(request.streetVehicles.find(_.mode == WALK).get)
      } else {
        request.streetVehiclesUseIntermodalUse match {
          case AccessAndEgress => request.streetVehicles.filter(_.mode != WALK)
          case _               => request.streetVehicles
        }
      }

      val maybeWalkToVehicleBuilder = Map.newBuilder[StreetVehicle, Option[EmbodiedBeamLeg]]
      val walkToVehicleDurationByVehicleBuilder = Map.newBuilder[StreetVehicle, Int]
      val bestWalkDurationByR5Mode = mutable.Map.empty[LegMode, Int]
      val bestAccessVehiclesByR5ModeBuilder = mutable.Map.empty[LegMode, StreetVehicle]
      accessVehicles.foreach { vehicle =>
        val maybeLeg = calcRouteToVehicle(vehicle)
        maybeWalkToVehicleBuilder += vehicle -> maybeLeg
        val walkDuration = maybeLeg.map(_.beamLeg.duration).getOrElse(0)
        walkToVehicleDurationByVehicleBuilder += vehicle -> walkDuration
        val legMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
        bestWalkDurationByR5Mode.get(legMode) match {
          case Some(bestDuration) if bestDuration <= walkDuration =>
          case _ =>
            bestWalkDurationByR5Mode.put(legMode, walkDuration)
            bestAccessVehiclesByR5ModeBuilder.put(legMode, vehicle)
        }
      }
      val maybeWalkToVehicle: Map[StreetVehicle, Option[EmbodiedBeamLeg]] = maybeWalkToVehicleBuilder.result()
      val walkToVehicleDurationByVehicle: Map[StreetVehicle, Int] = walkToVehicleDurationByVehicleBuilder.result()
      val bestAccessVehiclesByR5Mode: Map[LegMode, StreetVehicle] = bestAccessVehiclesByR5ModeBuilder.toMap

      val accessVehiclesToRoute = filterAccessVehiclesForPredeterminedMode(
        bestAccessVehiclesByR5Mode,
        request.requestedMode,
        request.withTransit,
        request.streetVehiclesUseIntermodalUse
      )

      val egressVehicles = if (mainRouteRideHailTransit) {
        request.streetVehiclesUseIntermodalUse match {
          case AccessAndEgress => request.streetVehicles.filter(_.mode != WALK)
          case _               => request.streetVehicles
        }
      } else if (request.withTransit) {
        request.possibleEgressVehicles :+ request.streetVehicles.find(_.mode == WALK).get
      } else {
        Vector()
      }

      val egressVehiclesToRoute = filterEgressVehiclesForPredeterminedMode(
        egressVehicles,
        request.requestedMode,
        request.withTransit,
        request.streetVehiclesUseIntermodalUse
      )
      val hasDriveOrBikeEgressVehicle = egressVehicles.exists(v => v.mode == CAR || v.mode == BIKE)

      val destinationVehicles = if (mainRouteToVehicle) {
        request.streetVehicles.filter(_.mode != WALK)
      } else {
        Vector()
      }
      if (request.withTransit) {
        profileRequest.transitModes = request.transitModes.getOrElse(util.EnumSet.allOf(classOf[TransitModes]))
      }

      val destinationVehicleAndLeg: Option[(StreetVehicle, EmbodiedBeamLeg)] =
        if (destinationVehicles.isEmpty) {
          None
        } else if (destinationVehicles.size == 1) {
          val onlyVehicle = destinationVehicles.head
          Some(onlyVehicle -> routeFromVehicleToDestination(onlyVehicle))
        } else {
          val candidateVehicleLegs = destinationVehicles.map(v => v -> routeFromVehicleToDestination(v))
          val (selectedVehicle, selectedLeg) = candidateVehicleLegs.minBy(_._2.beamLeg.duration)
          val candidatesStr = candidateVehicleLegs
            .map { case (vehicle, leg) => s"${vehicle.id}:${leg.beamLeg.duration}s" }
            .mkString("[", ", ", "]")
          logger.warn(
            s"[ROUTING-EGRESS-MULTI-VEHICLE] requestId=${request.requestId} " +
            s"streetVehiclesUseIntermodalUse=${request.streetVehiclesUseIntermodalUse} " +
            s"candidates=${destinationVehicles.size} durations=$candidatesStr selectedVehicle=${selectedVehicle.id}"
          )
          Some(selectedVehicle -> selectedLeg)
        }
      val destinationVehicle = destinationVehicleAndLeg.map(_._1)
      val vehicleToDestinationLeg = destinationVehicleAndLeg.map(_._2)

      val accessRouters = mutable.Map[LegMode, StreetRouter]()
      val accessStopsByMode = mutable.Map[LegMode, StopVisitor]()
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (vehicle <- accessVehiclesToRoute) {
        val theOrigin = if (mainRouteToVehicle || mainRouteRideHailTransit) {
          request.originUTM
        } else {
          vehicle.locationUTM.loc
        }
        val theDestination = if (mainRouteToVehicle) {
          destinationVehicle match {
            case Some(vehicle) => vehicle.locationUTM.loc
            case None =>
              logger.error("Route requested with egress vehicles that don't exist")
              request.destinationUTM
          }
        } else {
          request.destinationUTM
        }
        val originalFromWgs = geo.utm2Wgs(theOrigin)
        val originalToWgs = geo.utm2Wgs(theDestination)
        val accessSnapMode =
          if (request.withTransit && request.requestedMode.contains(DRIVE_TRANSIT)) {
            toR5StreetMode(vehicle.mode)
          } else {
            StreetMode.WALK
          }
        val from = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          originalFromWgs,
          linkRadiusMeters,
          accessSnapMode
        )
        val to = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          originalToWgs,
          linkRadiusMeters,
          accessSnapMode
        )
        profileRequest.fromLon = from.getX
        profileRequest.fromLat = from.getY
        profileRequest.toLon = to.getX
        profileRequest.toLat = to.getY

        val walkToVehicleDuration = walkToVehicleDurationByVehicle(vehicle)
        profileRequest.fromTime = request.departureTime + walkToVehicleDuration
        profileRequest.toTime =
          profileRequest.fromTime + 61 // Important to allow 61 seconds for transit schedules to be considered!
        val vehicleType = vehicleTypes(vehicle.vehicleTypeId)
        val (costPerMile, costPerMinute) = getVehicleCosts(vehicle)
        val r5mode = Modes.toR5StreetMode(vehicle.mode)
        val accessStatePool = transitAccessStatePools
          .get()
          .getOrElseUpdate(
            r5mode, {
              new StatePool(accessEgressStatePoolSize(r5mode)) // Created once per thread per mode
            }
          )
        val accessRoutingVariable =
          if (profileRequest.hasTransit) {
            StreetRouter.State.RoutingVariable.DURATION_SECONDS
          } else {
            StreetRouter.State.RoutingVariable.WEIGHT
          }
        // Borrow from pool instead of creating new
        val streetRouter = borrowRouterWithStatePool(
          getTravelTimeCalculator(vehicleType, shouldAddNoise = !profileRequest.hasTransit),
          travelCostCalculator(
            vehicleType,
            request.timeValueOfMoney,
            profileRequest.fromTime,
            costPerMile,
            costPerMinute
          ),
          accessStatePool, // ← Pass the specific state pool
          accessRoutingVariable
        )
        accessRoutersToReturn += (
          (
            streetRouter,
            accessStatePool,
            accessRoutingVariable
          )
        )

        if (vehicle.mode == BeamMode.BIKE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        streetRouter.profileRequest = profileRequest
        streetRouter.streetMode = toR5StreetMode(vehicle.mode)
        val legMode: LegMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
        driveTransitDiagnostics.recordAccessSnapModeIfTransit(
          legMode,
          profileRequest.hasTransit,
          accessSnapMode.toString
        )
        val calcDirectRoute = legMode match {
          case LegMode.WALK => buildDirectWalkRoute
          case LegMode.CAR  => buildDirectCarRoute
          case _            => true
        }
        driveTransitDiagnostics.recordAccessModeAttemptedIfTransit(legMode, profileRequest.hasTransit)
        val setOriginSuccess = streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon, linkRadiusMeters)
        if (setOriginSuccess) {
          if (profileRequest.hasTransit) {
            val destinationSplit = transportNetwork.streetLayer.findSplit(
              profileRequest.toLat,
              profileRequest.toLon,
              linkRadiusMeters,
              streetRouter.streetMode
            )
            val isDriveTransitAccessSearch =
              request.withTransit && request.requestedMode.contains(DRIVE_TRANSIT)
            val disableDestinationSplitBreakForThisAccessSearch =
              disableDestinationSplitBreakForDriveTransitAccess && isDriveTransitAccessSearch
            val destinationSplitExtraTimeSecondsAfterHit =
              if (isDriveTransitAccessSearch)
                driveTransitAccessDestinationSplitExtraTimeSecondsAfterHit
              else
                0
            val destinationSplitContinueIfStopsBelow =
              if (isDriveTransitAccessSearch)
                driveTransitAccessDestinationSplitContinueIfStopsBelow
              else
                0
            val stopVisitor = new StopVisitor(
              transportNetwork.streetLayer,
              streetRouter.quantityToMinimize,
              streetRouter.transitStopSearchQuantity,
              profileRequest.getMinTimeSeconds(streetRouter.streetMode),
              destinationSplit,
              stopAtDestinationSplit = !disableDestinationSplitBreakForThisAccessSearch,
              destinationSplitExtraTimeSecondsAfterHit = destinationSplitExtraTimeSecondsAfterHit,
              destinationSplitContinueIfStopsBelow = destinationSplitContinueIfStopsBelow
            )
            streetRouter.setRoutingVisitor(stopVisitor)
            streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(legMode)
            streetRouter.route()

            accessRouters.put(legMode, streetRouter) // For R5 API (keeps last per mode)
            accessStopsByMode.put(legMode, stopVisitor)
            driveTransitDiagnostics.recordAccessStopSearch(legMode, stopVisitor)
            if (calcDirectRoute && !mainRouteRideHailTransit) {
              // Not interested in direct options in the ride-hail-transit case,
              // only in the option where we actually use non-empty ride-hail for access and egress.
              // This is only for saving a computation, and only because the requests are structured like they are.
              if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
                val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
                if (lastState != null) {
                  val streetPath = new StreetPath(lastState, transportNetwork, false)
                  val streetSegment =
                    new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
                  directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
                } else if (profileRequest.streetTime * 60 > streetRouter.timeLimitSeconds) {
                  val vehicleType = vehicleTypes(vehicle.vehicleTypeId)
                  withRouter(
                    getTravelTimeCalculator(vehicleType, shouldAddNoise = !profileRequest.hasTransit),
                    travelCostCalculator(
                      vehicleType,
                      request.timeValueOfMoney,
                      profileRequest.fromTime,
                      costPerMile,
                      costPerMinute
                    )
                  ) { streetRouter =>
                    if (vehicle.mode == BeamMode.BIKE) {
                      streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
                    }
                    streetRouter.profileRequest = profileRequest
                    streetRouter.streetMode = toR5StreetMode(vehicle.mode)
                    streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
                    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon, linkRadiusMeters)
                    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)
                    streetRouter.route()
                    val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
                    if (lastState != null) {
                      val streetPath = new StreetPath(lastState, transportNetwork, false)
                      val streetSegment =
                        new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
                      directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
                    }
                  }
                }
              }
            }
          } else if (calcDirectRoute && !mainRouteRideHailTransit) {
            streetRouter.timeLimitSeconds =
              timeLimitForVehicleCategory(vehicleType.vehicleCategory, default = profileRequest.streetTime) * 60
            if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
              streetRouter.route()
              val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
              if (lastState != null) {
                val streetPath = new StreetPath(lastState, transportNetwork, false)
                val streetSegment =
                  new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
                directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
              }
            } else {
              val coord_str = s"lat:${profileRequest.toLat}, lon:${profileRequest.toLon}"
              logger.warn(s"Can't 'set destination' to coord $coord_str with streetRouter's maxDistance and mode.")
            }
          }
        } else if (profileRequest.hasTransit) {
          val failureDetails =
            s"setOrigin=false,snapMode=$accessSnapMode,streetMode=${streetRouter.streetMode}," +
            s"originalFrom=(${originalFromWgs.getY},${originalFromWgs.getX}),snappedFrom=(${from.getY},${from.getX})," +
            s"originalTo=(${originalToWgs.getY},${originalToWgs.getX}),snappedTo=(${to.getY},${to.getX})"
          driveTransitDiagnostics.recordAccessSetOriginFailure(legMode, failureDetails)
        }
      }

      directOption.summary = directOption.generateSummary
      profileResponse.addOption(directOption)

      if (profileRequest.hasTransit) {
        val egressRouters = mutable.Map[LegMode, StreetRouter]()
        val egressStopsByMode = mutable.Map[LegMode, StopVisitor]()
        profileRequest.reverseSearch = true
        for (vehicle <- egressVehiclesToRoute) {
          val (costPerMile, costPerMinute) = getVehicleCosts(vehicle)
          val theDestination = if (mainRouteToVehicle) {
            if (destinationVehicle.isDefined) {
              destinationVehicle.get.locationUTM.loc
            } else {
              logger.error("Route requested with egress vehicles that don't exist")
              request.destinationUTM
            }
          } else {
            request.destinationUTM
          }
          val to = geo.snapToR5Edge(
            transportNetwork.streetLayer,
            geo.utm2Wgs(theDestination),
            linkRadiusMeters
          )
          profileRequest.toLon = to.getX
          profileRequest.toLat = to.getY
          val vehicleType = vehicleTypes(vehicle.vehicleTypeId)
          val r5mode = Modes.toR5StreetMode(vehicle.mode)
          val egressStatePool = transitEgressStatePools
            .get()
            .getOrElseUpdate(
              r5mode, {
                new StatePool(accessEgressStatePoolSize(r5mode)) // Created once per thread per mode
              }
            )
          val streetRouter = borrowRouterWithStatePool(
            getTravelTimeCalculator(vehicleType, shouldAddNoise = !profileRequest.hasTransit),
            travelCostCalculator(
              vehicleType,
              request.timeValueOfMoney,
              profileRequest.fromTime,
              costPerMile,
              costPerMinute
            ),
            egressStatePool, // Pass the specific state pool
            StreetRouter.State.RoutingVariable.DURATION_SECONDS
          )

          egressRoutersToReturn += (
            (
              streetRouter,
              egressStatePool,
              StreetRouter.State.RoutingVariable.DURATION_SECONDS
            )
          )

          if (vehicle.mode == BeamMode.BIKE) {
            streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
          }
          val legMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
          streetRouter.streetMode = toR5StreetMode(vehicle.mode)
          streetRouter.profileRequest = profileRequest
          streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(legMode)
          val destinationSplit = transportNetwork.streetLayer.findSplit(
            profileRequest.fromLat,
            profileRequest.fromLon,
            linkRadiusMeters,
            streetRouter.streetMode
          )
          val stopVisitor = new StopVisitor(
            transportNetwork.streetLayer,
            streetRouter.quantityToMinimize,
            streetRouter.transitStopSearchQuantity,
            profileRequest.getMinTimeSeconds(streetRouter.streetMode),
            destinationSplit,
            stopAtDestinationSplit = !(mainRouteToVehicle && request.requestedMode.contains(DRIVE_TRANSIT))
          )
          streetRouter.setRoutingVisitor(stopVisitor)
          if (streetRouter.setOrigin(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
            streetRouter.route()
            egressRouters.put(legMode, streetRouter)
            egressStopsByMode.put(legMode, stopVisitor)
            driveTransitDiagnostics.recordEgressStopSearch(legMode, stopVisitor)
          }
        }

        val transitPaths = latency("getpath-transit-time", Metrics.VerboseLevel) {
          accessStopsByMode.flatMap { case (mode, stopVisitor) =>
            val isDriveTransitRequest =
              mode == LegMode.CAR || mode == LegMode.BICYCLE || hasDriveOrBikeEgressVehicle

            if (isDriveTransitRequest) {
              profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutesForDriveAccess
              profileRequest.maxRides = 2
            } else {
              profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutes
              profileRequest.maxRides = 3
            }

            val departureTimeToDominatingList: IntFunction[DominatingList] = (departureTime: Int) =>
              beamConfig.beam.routing.r5.transitAlternativeList.toLowerCase match {
                case "suboptimal" if !mainRouteRideHailTransit && !isDriveTransitRequest =>
                  // Note: We now disallow multiple responses for
                  // drive_transit. We should turn this back on if it is
                  // very important to the analysis
                  new SuboptimalDominatingList(
                    profileRequest.suboptimalMinutes
                  )
                case _ =>
                  new BeamDominatingList(
                    profileRequest.inRoutingFareCalculator,
                    Integer.MAX_VALUE,
                    departureTime + profileRequest.maxTripDurationMinutes * 60
                  )
              }

            mode match {
              case LegMode.CAR | LegMode.BICYCLE =>
                profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutesForDriveAccess
                profileRequest.maxRides = 2
              case _ if hasDriveOrBikeEgressVehicle =>
                profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutesForDriveAccess
                profileRequest.maxRides = 2
              case _ =>
                profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutes
                profileRequest.maxRides = 3
            }

            val modeSpecificBuffer = mode match {
              case LegMode.WALK         => beamConfig.beam.routing.r5.accessBufferTimeSeconds.walk
              case LegMode.BICYCLE      => beamConfig.beam.routing.r5.accessBufferTimeSeconds.bike
              case LegMode.BICYCLE_RENT => beamConfig.beam.routing.r5.accessBufferTimeSeconds.bike_rent
              case LegMode.CAR_PARK     => beamConfig.beam.routing.r5.accessBufferTimeSeconds.car
              case LegMode.CAR          => beamConfig.beam.routing.r5.accessBufferTimeSeconds.car
              case _                    => 0
            }
            profileRequest.fromTime = request.departureTime
            profileRequest.toTime = request.departureTime + modeSpecificBuffer + 61

            val accessTimesJava = new java.util.HashMap[LegMode, TIntIntMap]()
            accessTimesJava.put(mode, stopVisitor.stops)
            val accessModesSet = util.EnumSet.noneOf(classOf[LegMode])
            accessModesSet.add(mode)
            profileRequest.accessModes = accessModesSet

            val egressModesSet = util.EnumSet.noneOf(classOf[LegMode])
            val egressTimesJava = new java.util.HashMap[LegMode, TIntIntMap]()
            egressStopsByMode.foreach { case (m, visitor) =>
              egressTimesJava.put(m, visitor.stops)
              egressModesSet.add(m)
            }
            profileRequest.egressModes = egressModesSet

            val r5StreetMode = toR5StreetMode(mode)
            val modeTransitPaths =
              if (useMcRaptorRouterPooling) {
                val router = borrowMcRaptorRouter(
                  r5StreetMode,
                  profileRequest,
                  accessTimesJava,
                  egressTimesJava,
                  departureTimeToDominatingList,
                  null,
                  isDriveTransitRequest
                )
                val mcRaptorCallStarted = System.currentTimeMillis()
                try {
                  Try(router.getPaths.asScala) match {
                    case Success(p) => p
                    case Failure(e) =>
                      driveTransitDiagnostics.incrementMcRaptorExceptions()
                      handleMcRaptorGetPathsFailure(
                        mode,
                        e
                      )
                      Nil
                  }
                } finally {
                  val (statePoolExhaustions, statePoolMaxInUse, statePoolSize) =
                    returnMcRaptorRouter(router, r5StreetMode, isDriveTransitRequest)
                  val mcRaptorElapsedMs = System.currentTimeMillis() - mcRaptorCallStarted
                  val statePoolUtilization =
                    if (statePoolSize > 0) statePoolMaxInUse.toDouble / statePoolSize.toDouble else 0.0
                  if (logPoolPressureWarnings && (statePoolExhaustions > 0 || statePoolUtilization >= 0.9)) {
                    logger.warn(
                      s"[MCRAPTOR-POOL-PRESSURE] requestId=${request.requestId}, " +
                      s"mode=$mode, streetMode=$r5StreetMode, fromTime=${profileRequest.fromTime}, toTime=${profileRequest.toTime}, " +
                      s"elapsedMs=$mcRaptorElapsedMs, statePoolExhaustions=$statePoolExhaustions, " +
                      s"statePoolMaxInUse=$statePoolMaxInUse, statePoolSize=$statePoolSize, " +
                      f"statePoolUtilization=${statePoolUtilization * 100.0}%.1f%%, routerId=${System.identityHashCode(router)}"
                    )
                  }
                }
              } else {
                // Safety fallback: allocate fresh router per request mode.
                val router = new McRaptorSuboptimalPathProfileRouter(
                  transportNetwork,
                  profileRequest,
                  accessTimesJava,
                  egressTimesJava,
                  departureTimeToDominatingList,
                  null
                )
                val paths = Try(router.getPaths.asScala) match {
                  case Success(p) => p
                  case Failure(e) =>
                    driveTransitDiagnostics.incrementMcRaptorExceptions()
                    handleMcRaptorGetPathsFailure(
                      mode,
                      e
                    )
                    Nil
                }
                paths
              }
            driveTransitDiagnostics.recordTransitPathsByAccessMode(mode, modeTransitPaths.size)
            modeTransitPaths
          }

          // Catch IllegalStateException in R5.StatsCalculator
        }
        driveTransitDiagnostics.recordTransitPathsCount(transitPaths.size)

        for (transitPath <- transitPaths) {
          profileResponse.addTransitPath(
            accessRouters.asJava,
            egressRouters.asJava,
            transitPath,
            transportNetwork,
            profileRequest.getFromTimeDateZD
          )
        }

        latency("transfer-transit-time", Metrics.VerboseLevel) {
          generateStreetTransfersWithPooling(profileResponse, transportNetwork, profileRequest)
        }
      }
      try {
        profileResponse.recomputeStats(profileRequest)
      } catch {
        case e: IllegalStateException if e.getMessage != null && e.getMessage.contains("No valid itineraries") =>
          logger.debug(s"No transit paths found in calcRoute, returning ${profileResponse.getOptions.size} options")
      }

      val rawEmbodiedTrips = profileResponse.options.asScala.flatMap { option =>
        option.itinerary.asScala
          .map { itinerary =>
            // Using itinerary start as access leg's startTime
            val access = option.access.get(itinerary.connection.access)
            val transitAccessBuffer = access.mode match {
              case _ if option.transit == null => 0
              case LegMode.WALK                => beamConfig.beam.routing.r5.accessBufferTimeSeconds.walk
              case LegMode.BICYCLE             => beamConfig.beam.routing.r5.accessBufferTimeSeconds.bike
              case LegMode.BICYCLE_RENT        => beamConfig.beam.routing.r5.accessBufferTimeSeconds.bike_rent
              case LegMode.CAR_PARK            => beamConfig.beam.routing.r5.accessBufferTimeSeconds.car
              case LegMode.CAR if mainRouteRideHailTransit =>
                beamConfig.beam.routing.r5.accessBufferTimeSeconds.ride_hail
              case LegMode.CAR => beamConfig.beam.routing.r5.accessBufferTimeSeconds.car
              case _           => 0
            }
            val tripStartTime = dates
              .toBaseMidnightSeconds(
                itinerary.startTime,
                transportNetwork.transitLayer.routes.size() == 0
              )
              .toInt - transitAccessBuffer

            var arrivalTime: Int = Int.MinValue
            val embodiedBeamLegs = mutable.ArrayBuffer.empty[EmbodiedBeamLeg]
            val vehicle = bestAccessVehiclesByR5Mode(access.mode)

            maybeWalkToVehicle(vehicle).foreach(walkLeg => {
              // Glue the walk to vehicle in front of the trip without a gap
              embodiedBeamLegs += walkLeg
                .copy(beamLeg = walkLeg.beamLeg.updateStartTime(tripStartTime - walkLeg.beamLeg.duration))
            })

            val accessLegCost = if (isRideHail(vehicle.id)) {
              Some(
                getRideHailCost(
                  RIDE_HAIL,
                  access.distance / 1000,
                  access.duration,
                  getFleetName(vehicle.id),
                  beamConfig
                )
              )
            } else None

            embodiedBeamLegs += buildStreetBasedLegs(
              access,
              tripStartTime,
              vehicle,
              unbecomeDriverOnCompletion = access.mode != LegMode.WALK || option.transit == null,
              costOverride = accessLegCost
            )

            arrivalTime = embodiedBeamLegs.last.beamLeg.endTime

            val transitSegments = Optional.ofNullable(option.transit).orElse(Collections.emptyList()).asScala
            val transitJourneyIDs =
              Optional.ofNullable(itinerary.connection.transit).orElse(Collections.emptyList()).asScala
            // Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
            // assuming that: For each transit in option there is a TransitJourneyID in connection
            val segments = transitSegments zip transitJourneyIDs

            // Lazy because this looks expensive and we may not need it because there's _another_ fare
            // calculation that takes precedence
            lazy val fares = latency("fare-transit-time", Metrics.VerboseLevel) {
              val fareSegments = getFareSegments(segments.toVector)
              filterFaresOnTransfers(fareSegments)
            }
            segments.foreach { case (transitSegment, transitJourneyID) =>
              val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
              val tripPattern = profileResponse.getPatterns.asScala
                .find { tp =>
                  tp.getTripPatternIdx == segmentPattern.patternIdx
                }
                .getOrElse(throw new RuntimeException())
              val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
              val route = transportNetwork.transitLayer.routes.get(tripPattern.getRouteIdx)
              val r5TripPattern = transportNetwork.transitLayer.tripPatterns.get(tripPattern.getTripPatternIdx)

              val stopSequence = (segmentPattern.fromIndex to segmentPattern.toIndex).map { idx =>
                new Stop(r5TripPattern.stops(idx), transportNetwork.transitLayer)
              }.toList

              val fromStop = stopSequence.head
              val toStop = stopSequence.last

              val startTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              val endTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt

              var totalDistance = 0.0
              var i = 0
              while (i < stopSequence.length - 1) {
                totalDistance += getDistanceBetweenStops(stopSequence(i), stopSequence(i + 1))
                i += 1
              }

              val segmentLeg = BeamLeg(
                startTime,
                Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type)),
                java.time.temporal.ChronoUnit.SECONDS
                  .between(
                    segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                    segmentPattern.toArrivalTime.get(transitJourneyID.time)
                  )
                  .toInt,
                BeamPath(
                  Array[Int](),
                  Array[Double](),
                  Some(
                    TransitStopsInfo(
                      route.agency_id,
                      tripPattern.getRouteId,
                      Id.createVehicleId(tripId),
                      segmentPattern.fromIndex,
                      segmentPattern.toIndex
                    )
                  ),
                  SpaceTime(fromStop.lon, fromStop.lat, startTime),
                  SpaceTime(toStop.lon, toStop.lat, endTime),
                  totalDistance
                )
              )
              embodiedBeamLegs += EmbodiedBeamLeg(
                segmentLeg,
                segmentLeg.travelPath.transitStops.get.vehicleId,
                null,
                asDriver = false,
                ptFares
                  .getPtFare(
                    Some(segmentLeg.travelPath.transitStops.get.agencyId),
                    Some(segmentLeg.travelPath.transitStops.get.routeId),
                    request.attributesOfIndividual.flatMap(_.age)
                  )
                  .getOrElse {
                    val fs =
                      fares.view
                        .filter(_.patternIndex == segmentPattern.patternIdx)
                        .map(_.fare.price)
                    if (fs.nonEmpty) fs.min else 0.0
                  },
                unbecomeDriverOnCompletion = false
              )
              arrivalTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              if (transitSegment.middle != null) {
                val body = request.streetVehicles.find(_.mode == WALK).get
                embodiedBeamLegs += buildStreetBasedLegs(
                  transitSegment.middle,
                  arrivalTime,
                  body,
                  unbecomeDriverOnCompletion = false
                )
                arrivalTime = arrivalTime + transitSegment.middle.duration
              }
            }

            if (itinerary.connection.egress != null) {
              val egress = option.egress.get(itinerary.connection.egress)
              val vehicle =
                egressVehicles
                  .find(v => v.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf("")) == egress.mode)
                  .get

              val egressLegCost = if (isRideHail(vehicle.id)) {
                Some(
                  getRideHailCost(
                    RIDE_HAIL,
                    access.distance / 1000,
                    access.duration,
                    getFleetName(vehicle.id),
                    beamConfig
                  )
                )
              } else None

              embodiedBeamLegs += buildStreetBasedLegs(
                egress,
                arrivalTime,
                vehicle,
                unbecomeDriverOnCompletion = true,
                costOverride = egressLegCost
              )
              val body = request.streetVehicles.find(_.mode == WALK).get
              if (isRouteForPerson && egress.mode != LegMode.WALK) {
                embodiedBeamLegs += EmbodiedBeamLeg(
                  dummyLeg(arrivalTime + egress.duration, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
                  body.id,
                  body.vehicleTypeId,
                  body.asDriver,
                  0.0,
                  unbecomeDriverOnCompletion = true
                )
              }
            }

            vehicleToDestinationLeg.foreach { legWithFare =>
              // Glue the drive to the final destination behind the trip without a gap
              embodiedBeamLegs += legWithFare.copy(
                beamLeg = legWithFare.beamLeg.updateStartTime(embodiedBeamLegs.last.beamLeg.endTime)
              )
            }
            if (isRouteForPerson && embodiedBeamLegs.last.beamLeg.mode != WALK) {
              val body = request.streetVehicles.find(_.mode == WALK).get
              embodiedBeamLegs += EmbodiedBeamLeg(
                dummyLeg(embodiedBeamLegs.last.beamLeg.endTime, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
                body.id,
                body.vehicleTypeId,
                body.asDriver,
                0.0,
                unbecomeDriverOnCompletion = true
              )
            }
            EmbodiedBeamTrip(embodiedBeamLegs, Some("R5"))
          }
          .filter { trip: EmbodiedBeamTrip =>
            // Allow lower-frequency service to remain available in late-start itineraries.
            trip.legs.forall(l =>
              l.beamLeg.startTime >= request.departureTime
            ) && trip.legs.head.beamLeg.startTime <= request.departureTime + 3600
          }
      }

      val embodiedTrips = deduplicateItineraries(rawEmbodiedTrips.toVector)
      driveTransitDiagnostics.recordRouteOutcome(
        requestId = request.requestId,
        accessVehiclesToRouteCount = accessVehiclesToRoute.size,
        embodiedTrips = embodiedTrips
      )

      val modesWeSearched =
        searchedModes(request, buildDirectCarRoute, buildDirectWalkRoute, isRouteForPerson, mainRouteRideHailTransit)

      val routingResponse = if (!embodiedTrips.exists(_.tripClassifier == WALK) && !mainRouteToVehicle) {
        val maybeBody = accessVehicles.find(_.mode == WALK)
        if (
          buildDirectWalkRoute && maybeBody.isDefined && (request.requestedMode.isEmpty || request.requestedMode
            .contains(WALK))
        ) {
          val dummyTrip = RoutingWorker.createBushwackingTrip(
            new Coord(request.originUTM.getX, request.originUTM.getY),
            new Coord(request.destinationUTM.getX, request.destinationUTM.getY),
            request.departureTime,
            maybeBody.get,
            geo
          )
          RoutingResponse(
            embodiedTrips :+ dummyTrip,
            request.requestId,
            Some(request),
            isEmbodyWithCurrentTravelTime = false,
            System.currentTimeMillis() - routeCalcStarted,
            modesWeSearched,
            request.triggerId
          )
        } else {
          RoutingResponse(
            embodiedTrips,
            request.requestId,
            Some(request),
            isEmbodyWithCurrentTravelTime = false,
            System.currentTimeMillis() - routeCalcStarted,
            modesWeSearched,
            request.triggerId
          )
        }
      } else {
        RoutingResponse(
          embodiedTrips,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false,
          System.currentTimeMillis() - routeCalcStarted,
          modesWeSearched,
          request.triggerId
        )
      }

      routingResponse
    } finally {
      accessRoutersToReturn.foreach { case (router, pool, qtm) =>
        returnRouterWithStatePool(router, pool, qtm)
      }

      egressRoutersToReturn.foreach { case (router, pool, qtm) =>
        returnRouterWithStatePool(router, pool, qtm)
      }
    }
  }

  // In R5Wrapper.scala - add this method:

  /**
    * Override ProfileResponse to use pooled routers for street transfers
    */
  private def generateStreetTransfersWithPooling(
    profileResponse: ProfileResponse,
    transportNetwork: TransportNetwork,
    profileRequest: ProfileRequest
  ): Unit = {

    val transfersToOptions = profileResponse.getTransferToOption

    val transfersByStart = mutable.Map.empty[Int, ArrayBuffer[Transfer]]
    val transferIterator = transfersToOptions.keySet().iterator()
    while (transferIterator.hasNext) {
      val transfer = transferIterator.next()
      val groupedTransfers =
        transfersByStart.getOrElseUpdate(transfer.getAlightStop, ArrayBuffer.empty[Transfer])
      groupedTransfers += transfer
    }
    val walkVehicleType = vehicleTypes(walkVehicleTypeId)
    val walkTravelTimeCalculator = getTravelTimeCalculator(walkVehicleType, shouldAddNoise = false)
    val walkTravelCostCalculator = travelCostCalculator(walkVehicleType, 0, profileRequest.fromTime, 0, 0)
    val mainPool = mainRoutingPool.get()
    val routingVariable = StreetRouter.State.RoutingVariable.DURATION_SECONDS

    val prevReverseSearch = profileRequest.reverseSearch
    profileRequest.reverseSearch = false

    try {
      transfersByStart.foreach { case (alightStopIdx, transfers) =>
        // Borrow router from pool instead of creating new
        val streetRouter = borrowRouterWithStatePool(
          walkTravelTimeCalculator,
          walkTravelCostCalculator,
          mainPool,
          routingVariable
        )

        try {
          streetRouter.streetMode = StreetMode.WALK
          streetRouter.profileRequest = profileRequest
          streetRouter.distanceLimitMeters = TransitLayer.TRANSFER_DISTANCE_LIMIT_METERS

          val stopIndex = transportNetwork.transitLayer.streetVertexForStop.get(alightStopIdx)
          streetRouter.setOrigin(stopIndex)
          streetRouter.route()

          // Add paths to profile options
          transfers.foreach { transfer =>
            val endIndex = transportNetwork.transitLayer.streetVertexForStop.get(transfer.boardStop)
            // Skip transfers to stops not linked to the street network (common with clipped graphs).
            if (endIndex != -1) {
              // Cache key based on origin-destination pair
              val cacheKey = (alightStopIdx, transfer.boardStop)
              transferSegmentCache.get(cacheKey) match {
                case Some(streetSegment) =>
                  transfersToOptions.get(transfer).asScala.foreach { profileOption =>
                    profileOption.addMiddle(streetSegment, transfer)
                  }
                case None =>
                  // Only create if not cached
                  val lastState = streetRouter.getStateAtVertex(endIndex)
                  if (lastState != null) {
                    val streetPath = new StreetPath(lastState, transportNetwork, false)
                    val streetSegment = new StreetSegment(streetPath, LegMode.WALK, transportNetwork.streetLayer)
                    transferSegmentCache.put(cacheKey, streetSegment)
                    transfersToOptions.get(transfer).asScala.foreach { profileOption =>
                      profileOption.addMiddle(streetSegment, transfer)
                    }
                  }
              }
            }
          }

        } finally {
          //  Return router to pool
          returnRouterWithStatePool(
            streetRouter,
            mainPool,
            routingVariable
          )
        }
      }
    } finally {
      profileRequest.reverseSearch = prevReverseSearch
    }
  }

  private def timeLimitForVehicleCategory(vehicleCategory: VehicleCategory, default: Int): Int = {

    /**
      * maxTimeLimitForFreightInMinutes is a cutoff where R5 stops trying to find a route if the shortest route
      * it longer than that value. It'll keep routing time from blowing up for really long routes.
      * we override the default for freight because there are more really long trips that
      * the router was failing to return routes for.
      */
    vehicleCategory match {
      case VehicleCategory.Class456Vocational => beamConfig.beam.routing.r5.maxTimeLimitForFreightInMinutes
      case VehicleCategory.Class78Vocational  => beamConfig.beam.routing.r5.maxTimeLimitForFreightInMinutes
      case VehicleCategory.Class78Tractor     => beamConfig.beam.routing.r5.maxTimeLimitForFreightInMinutes
      case _                                  => default
    }
  }

  /**
    * Filters access vehicles to only route modes that match the predetermined mode choice.
    * Prevents routing unwanted mode alternatives (e.g., WALK when mode is predetermined as CAR).
    *
    * @param bestAccessVehiclesByR5Mode Map of R5 leg modes to their best street vehicles.
    * @param requestedMode Optional predetermined BeamMode for this trip.
    * @param withTransit Whether this is a transit routing request.
    * @param streetVehiclesUseIntermodalUse How vehicles are being used (Access/Egress/Both).
    * @return Filtered collection of vehicles to route for transit access.
    */
  private def filterAccessVehiclesForPredeterminedMode(
    bestAccessVehiclesByR5Mode: Map[LegMode, StreetVehicle],
    requestedMode: Option[BeamMode],
    withTransit: Boolean,
    streetVehiclesUseIntermodalUse: IntermodalUse
  ): Iterable[StreetVehicle] = {
    @inline def modeVehicle(mode: LegMode): Iterable[StreetVehicle] =
      bestAccessVehiclesByR5Mode.get(mode).toIterable

    requestedMode match {
      // Walk only - filter to walk mode
      case Some(WALK) =>
        modeVehicle(LegMode.WALK)

      // Car without transit - only route car, not walk alternative
      case Some(CAR | CAR_HOV2 | CAR_HOV3) if !withTransit =>
        modeVehicle(LegMode.CAR)

      // Bike without transit - only route bike, not walk alternative
      case Some(BIKE) if !withTransit =>
        modeVehicle(LegMode.BICYCLE)

      // Walk + Transit - only route walk for access
      case Some(WALK_TRANSIT) if withTransit =>
        modeVehicle(LegMode.WALK)

      // Drive + Transit on first trip (access) - only route car for access
      case Some(DRIVE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Access =>
        modeVehicle(LegMode.CAR)

      // Drive + Transit on last trip (egress) - only route walk for access
      // (Car will be used for post-transit leg via destinationVehicles)
      case Some(DRIVE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Egress =>
        modeVehicle(LegMode.WALK)

      // Bike + Transit on first trip (access) - only route bike for access
      case Some(BIKE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Access =>
        modeVehicle(LegMode.BICYCLE)

      // Bike + Transit on last trip (egress) - only route walk for access
      // (Bike will be used for post-transit leg via destinationVehicles)
      case Some(BIKE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Egress =>
        modeVehicle(LegMode.WALK)

      // Ride hail or ride hail transit - only walk (RH handled separately)
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED | RIDE_HAIL_TRANSIT) =>
        modeVehicle(LegMode.WALK)

      // No predetermined mode or unhandled case - route all modes
      case _ =>
        bestAccessVehiclesByR5Mode.values
    }
  }

  /**
    * Filters egress vehicles to only route modes that match the predetermined mode choice.
    * Prevents routing shared vehicles or alternative modes when mode is already determined.
    *
    * @param egressVehicles All available egress vehicles.
    * @param requestedMode Optional predetermined BeamMode for this trip.
    * @param withTransit Whether this is a transit routing request.
    * @param streetVehiclesUseIntermodalUse How vehicles are being used (Access/Egress/Both).
    * @return Filtered collection of vehicles to route for transit egress.
    */
  private def filterEgressVehiclesForPredeterminedMode(
    egressVehicles: IndexedSeq[StreetVehicle],
    requestedMode: Option[BeamMode],
    withTransit: Boolean,
    streetVehiclesUseIntermodalUse: IntermodalUse
  ): IndexedSeq[StreetVehicle] = {
    requestedMode match {
      // Walk or Walk + Transit - only walk for egress, filter out shared vehicles
      case Some(WALK | WALK_TRANSIT) if withTransit =>
        egressVehicles.filter(_.mode == WALK)

      // Drive + Transit on first trip (access) - only walk for egress
      // (Person walks from transit to final destination after driving to station)
      case Some(DRIVE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Access =>
        egressVehicles.filter(_.mode == WALK)

      // Drive + Transit on last trip (egress) - only walk for egress to parked car
      // (Person walks from transit stop to parked car, then drives)
      case Some(DRIVE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Egress =>
        egressVehicles.filter(_.mode == WALK)

      // Bike + Transit on first trip (access) - only walk for egress
      case Some(BIKE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Access =>
        egressVehicles.filter(_.mode == WALK)

      // Bike + Transit on last trip (egress) - only walk for egress to parked bike
      case Some(BIKE_TRANSIT) if withTransit && streetVehiclesUseIntermodalUse == Egress =>
        egressVehicles.filter(_.mode == WALK)

      // Ride hail transit - filter based on whether we're doing RH access/egress
      case Some(RIDE_HAIL_TRANSIT) if withTransit =>
        // Keep only walk and ride hail dummy vehicles
        egressVehicles.filter(v => v.mode == WALK || isRideHail(v.id))

      // No predetermined mode or unhandled case - route all egress modes
      case _ =>
        egressVehicles
    }
  }

  /**
    * Filters which modes should be routed as direct options by R5.
    * Prevents R5 from routing modes that will be discarded anyway.
    *
    * @param modes All modes that could theoretically be routed.
    * @param requestedMode Optional predetermined BeamMode.
    * @param withTransit Whether this is a transit request.
    * @return Filtered modes to actually route.
    */
  private def filterDirectModesForPredeterminedMode(
    modes: Seq[LegMode],
    requestedMode: Option[BeamMode],
    withTransit: Boolean
  ): Seq[LegMode] = {
    requestedMode match {
      // Walk only - only route walk
      case Some(WALK) =>
        modes.filter(_ == LegMode.WALK)

      // Car only (non-transit) - only route car, not walk
      case Some(CAR | CAR_HOV2 | CAR_HOV3 | FREIGHT) if !withTransit =>
        modes.filter(_ == LegMode.CAR)

      // Bike only (non-transit) - only route bike, not walk
      case Some(BIKE) if !withTransit =>
        modes.filter(_ == LegMode.BICYCLE)

      // Transit modes - don't route direct alternatives at all
      // (They'll be handled by the transit-specific routing)
      case Some(WALK_TRANSIT | DRIVE_TRANSIT | BIKE_TRANSIT) if withTransit =>
        Seq.empty // No direct routes needed for pure transit trips

      // Ride hail - only walk (RH handled separately)
      case Some(RIDE_HAIL | RIDE_HAIL_POOLED | RIDE_HAIL_TRANSIT) =>
        modes.filter(_ == LegMode.WALK)

      // No predetermined mode - route all available modes
      case _ =>
        modes
    }
  }

  private def searchedModes(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean,
    isRouteForPerson: Boolean,
    mainRouteRideHailTransit: Boolean
  ): Set[BeamMode] = {
    val searchedModes: Set[BeamMode] = if (mainRouteRideHailTransit) {
      Set(RIDE_HAIL_TRANSIT)
    } else if (!isRouteForPerson) {
      Set(RIDE_HAIL)
    } else {
      val hasBike = request.streetVehicles.exists(_.mode == BIKE)
      val hasCar = request.streetVehicles.exists(_.mode == CAR)
      val modes: Set[BeamMode] = (hasBike, hasCar) match {
        case (false, false) => Set(WALK)
        case (true, false)  => Set(WALK, BIKE)
        case (false, true)  => Set(WALK, CAR)
        case (true, true)   => Set(WALK, BIKE, CAR)
      }
      if (request.withTransit) modes + TRANSIT else modes
    }
    (buildDirectWalkRoute, buildDirectCarRoute) match {
      case (true, true)   => searchedModes
      case (false, false) => searchedModes - WALK - CAR
      case (false, true)  => searchedModes - WALK
      case (true, false)  => searchedModes - CAR
    }
  }

  private def getDistanceBetweenStops(fromStop: Stop, toStop: Stop): Double = {
    geo.distLatLon2Meters(fromStop.lon, fromStop.lat, toStop.lon, toStop.lat)
  }

  private def buildStreetBasedLegs(
    segment: StreetSegment,
    tripStartTime: Int,
    vehicle: StreetVehicle,
    unbecomeDriverOnCompletion: Boolean,
    costOverride: Option[Double] = None
  ): EmbodiedBeamLeg = {
    val startPoint = SpaceTime(
      segment.geometry.getStartPoint.getX,
      segment.geometry.getStartPoint.getY,
      tripStartTime
    )
    val endCoord = new Coord(
      segment.geometry.getEndPoint.getX,
      segment.geometry.getEndPoint.getY
    )

    val activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds += edge.edgeId.intValue()
    }

    val beamLeg: BeamLeg =
      createBeamLeg(vehicle.vehicleTypeId, startPoint, endCoord, segment.mode, activeLinkIds, Some(vehicle.mode))
    val toll = if (segment.mode == LegMode.CAR) {
      val osm = segment.streetEdges.asScala
        .map(e =>
          transportNetwork.streetLayer.edgeStore
            .getCursor(e.edgeId)
            .getOSMID
        )
        .toVector
      tollCalculator.calcTollByOsmIds(osm) + tollCalculator.calcTollByLinkIds(beamLeg.travelPath)
    } else 0.0
    val drivingCost = costOverride.getOrElse(if (segment.mode == LegMode.CAR || vehicle.needsToCalculateCost) {
      val vehicleType = vehicleTypes(vehicle.vehicleTypeId)
      DrivingCost.estimateDrivingCost(
        beamLeg.travelPath.distanceInM,
        beamLeg.duration,
        vehicleType,
        fuelTypePrices.getOrElse(vehicleType.primaryFuelType, 0.0)
      )
    } else 0.0)
    EmbodiedBeamLeg(
      beamLeg,
      vehicle.id,
      vehicle.vehicleTypeId,
      vehicle.asDriver,
      drivingCost + toll,
      unbecomeDriverOnCompletion
    )
  }

  private def createBeamLeg(
    vehicleTypeId: Id[BeamVehicleType],
    startPoint: SpaceTime,
    endCoord: Coord,
    legMode: LegMode,
    activeLinkIds: IndexedSeq[Int],
    maybeVehicleMode: Option[BeamMode] = None
  ): BeamLeg = {
    val tripStartTime: Int = startPoint.time

    val linksTimesDistances = RoutingModel.linksToTimeAndDistance(
      activeLinkIds,
      tripStartTime,
      travelTimeByLinkCalculator(vehicleTypes(vehicleTypeId), shouldAddNoise = false),
      toR5StreetMode(legMode),
      transportNetwork.streetLayer
    )

    // Manual sum to avoid boxing (skip first link per MATSim convention)
    var distance = 0.0
    var i = 1
    while (i < linksTimesDistances.distances.length) {
      distance += linksTimesDistances.distances(i)
      i += 1
    }

    // Manual sum for travel times (skip first link)
    var totalTravelTime = 0.0
    var j = 1
    while (j < linksTimesDistances.travelTimes.length) {
      totalTravelTime += linksTimesDistances.travelTimes(j)
      j += 1
    }

    val theTravelPath = BeamPath(
      linkIds = linksTimesDistances.linkIds, // Already Array[Int]
      linkTravelTime = linksTimesDistances.travelTimes, // Already Array[Double]
      transitStops = None,
      startPoint = startPoint,
      endPoint = SpaceTime(
        endCoord,
        startPoint.time + math.round(totalTravelTime.toFloat)
      ),
      distanceInM = distance
    )

    val newLegMode = maybeVehicleMode match {
      case Some(vehicleMode @ CAR) => vehicleMode
      case _                       => mapLegMode(legMode)
    }

    val beamLeg = BeamLeg(
      tripStartTime,
      newLegMode,
      theTravelPath.duration,
      travelPath = theTravelPath
    )
    beamLeg
  }

  private def deduplicateItineraries(trips: Vector[EmbodiedBeamTrip]): Vector[EmbodiedBeamTrip] = {
    // Group trips by their vehicle sequences (ignoring minor timing differences)
    val grouped = trips.groupBy { trip =>
      val transitVehicleIds = trip.legs.filter(_.beamLeg.mode.isTransit).map(_.beamVehicleId).sorted
      if (transitVehicleIds.nonEmpty) transitVehicleIds else trip.legs.map(_.beamVehicleId).sorted
    }

    // For each group, keep only the trip with the earliest reasonable arrival time
    grouped.values.map { similarTrips =>
      similarTrips.minBy(_.legs.last.beamLeg.endTime)
    }.toVector
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments IndexedSeq[(TransitSegment, TransitJourneyID)]
    * @return a collection of FareSegments for an itinerary.
    */
  private def getFareSegments(
    segments: IndexedSeq[(TransitSegment, TransitJourneyID)]
  ): IndexedSeq[BeamFareSegment] = {
    segments
      .groupBy(s => getRoute(s._1, s._2).agency_id)
      .flatMap(t => {
        val pattern = getPattern(t._2.head._1, t._2.head._2)
        val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)

        var rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

        if (rules.isEmpty) {
          val route = getRoute(pattern)
          val agencyId = route.agency_id
          val routeId = route.route_id

          val fromId = getStopId(t._2.head._1.from)
          val toId = getStopId(t._2.last._1.to)

          val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime
            .get(t._2.last._2.time)
          val duration = ChronoUnit.SECONDS.between(fromTime, toTime)

          val containsIds =
            t._2
              .flatMap(s => IndexedSeq(getStopId(s._1.from), getStopId(s._1.to)))
              .toSet

          rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds)
            .map(f => BeamFareSegment(f, pattern.patternIdx, duration))
        }
        rules
      })
      .toIndexedSeq
  }

  private def getFareSegments(
    transitSegment: TransitSegment,
    transitJourneyID: TransitJourneyID,
    fromTime: ZonedDateTime
  ): IndexedSeq[BeamFareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration =
      ChronoUnit.SECONDS
        .between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    calculateFr(pattern, routeId, agencyId, fromStopId, toStopId, duration)
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  private def calculateFr(
    pattern: SegmentPattern,
    routeId: String,
    agencyId: String,
    fromStopId: String,
    toStopId: String,
    duration: Long
  ): IndexedSeq[BeamFareSegment] = {
    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(f =>
      BeamFareSegment(f, pattern.patternIdx, duration)
    )
    if (fr.nonEmpty && fr.forall(_.patternIndex == fr.head.patternIndex)) {
      fr = Vector(fr.minBy(_.fare.price))
    }
    fr
  }

  private def getFareSegments(
    agencyId: String,
    routeId: String,
    fromId: String,
    toId: String,
    containsIds: Set[String] = null
  ): IndexedSeq[BeamFareSegment] =
    fareCalculator.getFareSegments(agencyId, routeId, fromId, toId, containsIds)

  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes
      .get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

  private def getVehicleCosts(
    vehicle: StreetVehicle
  ): (Double, Double) = {
    val (costPerMile, costPerMinute, _) = vehicle.mode match {
      case CAR if isRideHail(vehicle.id) => getRideHailManagerCosts(RIDE_HAIL, getFleetName(vehicle.id), beamConfig)
      case _                             => (0.0, 0.0, 0.0)
    }
    (costPerMile, costPerMinute)
  }

  private def getTravelTimeCalculator(
    vehicleType: BeamVehicleType,
    shouldAddNoise: Boolean,
    shouldApplyBicycleScaleFactor: Boolean = true
  ): TravelTimeCalculator = {
    val config = getRoutingConfig(vehicleType, shouldAddNoise, shouldApplyBicycleScaleFactor)

    travelTimeCalculatorCache
      .get()
      .getOrElseUpdate(
        config, {
          val ttc = travelTimeByLinkCalculator(vehicleType, shouldAddNoise, shouldApplyBicycleScaleFactor)
          new BeamTravelTimeCalculator(vehicleType, shouldAddNoise, shouldApplyBicycleScaleFactor, ttc)
        }
      )
  }

  private def travelTimeByLinkCalculator(
    vehicleType: BeamVehicleType,
    shouldAddNoise: Boolean,
    shouldApplyBicycleScaleFactor: Boolean = false
  ): TravelTimeByLinkCalculator = {
    val profileRequest = createProfileRequest

    val walkSpeed = profileRequest.getSpeedForMode(StreetMode.WALK)
    val bikeSpeed = profileRequest.getSpeedForMode(StreetMode.BICYCLE)
    val vehicleMaxSpeed = vehicleType.maxVelocity.getOrElse(Double.MaxValue)
    val carSpeed = Math.min(vehicleMaxSpeed, profileRequest.getSpeedForMode(StreetMode.CAR))

    (time: Double, linkId: Int, streetMode: StreetMode) => {
      val edgeLength = transportNetwork.streetLayer.edgeStore.lengths_mm.get(linkId / 2) / 1000.0
      val modeSpeed =
        if (streetMode == StreetMode.CAR) {
          carSpeed
        } else if (streetMode == StreetMode.BICYCLE) {
          bikeSpeed
        } else {
          walkSpeed
        }
      val minTravelTime = edgeLength / modeSpeed

      if (streetMode == StreetMode.BICYCLE && shouldApplyBicycleScaleFactor) {
        //note we're not explicitly checking that it is a Bike VehicleType
        minTravelTime * bikeScaleFactor.scaleFactor(linkId)
      } else if (streetMode == StreetMode.CAR) {
        carWeightCalculator.calcTravelTime(linkId, travelTime, modeSpeed, time, shouldAddNoise, edgeLength)
      } else {
        minTravelTime
      }
    }
  }

  private val turnCostCalculator: TurnCostCalculator =
    new TurnCostCalculator(transportNetwork.streetLayer, true) {
      override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
    }

  private def travelCostCalculator(
    vehicleType: BeamVehicleType,
    timeValueOfMoney: Double,
    startTime: Int,
    perMileCost: Double = 0.0,
    perMinuteCost: Double = 0.0
  ): TravelCostCalculator = {
    val vehicleCategory = vehicleType.vehicleCategory
    val category = RoutingVehicleCategory.fromCategory(vehicleType.vehicleCategory)
    val categoryRestrictions = precomputedRestrictions(category)
    val hasPrecomputedRestrictions = category != RoutingVehicleCategory.Other
    val weightMultiplier = workerParams.beamConfig.beam.agentsim.agents.vehicles.roadRestrictionWeightMultiplier.toFloat

    val maxSpeedOrNegative = vehicleType.restrictRoadsByFreeSpeedInMeterPerSecond.getOrElse(-1.0)
    val hasSpeedRestriction = maxSpeedOrNegative >= 0
    val hasAnyRoadRestrictions = hasPrecomputedRestrictions || hasSpeedRestriction

    val perMileCostFactor = perMileCost / METERS_IN_MILE
    val perMinuteCostFactor = perMinuteCost / 60.0
    val hasFareComponent = perMileCostFactor != 0.0 || perMinuteCostFactor != 0.0
    val hasAnyTolls = tollCalculator.hasAnyTolls
    val appliesMonetaryCosts = timeValueOfMoney != 0.0 && (hasFareComponent || hasAnyTolls)

    @inline
    def roadRestrictionMultiplier(edge: EdgeStore#Edge): Float = {
      val osmId = edge.getOSMID
      if (hasPrecomputedRestrictions && categoryRestrictions.get(osmId) == 1) {
        weightMultiplier
      } else if (hasSpeedRestriction) {
        val restriction = osmIdToRoadRestrictionTrove.get(osmId)
        if (restriction != null && restriction.isRestricted(vehicleCategory, maxSpeedOrNegative)) {
          weightMultiplier
        } else {
          1f
        }
      } else {
        1f
      }
    }

    @inline
    def generalizedTraversalCost(
      edge: EdgeStore#Edge,
      legDurationSeconds: Int,
      traversalTimeSeconds: Float
    ): Float = {
      val fare =
        if (hasFareComponent) traversalTimeSeconds * perMinuteCostFactor + edge.getLengthM * perMileCostFactor else 0.0
      val toll =
        if (hasAnyTolls) tollCalculator.calcTollByLinkId(edge.getEdgeIndex, startTime + legDurationSeconds) else 0.0
      traversalTimeSeconds + (timeValueOfMoney * (toll + fare)).toFloat
    }

    if (!hasAnyRoadRestrictions && !appliesMonetaryCosts) { (_: EdgeStore#Edge, _: Int, traversalTimeSeconds: Float) =>
      traversalTimeSeconds
    } else if (!hasAnyRoadRestrictions) {
      (edge: EdgeStore#Edge, legDurationSeconds: Int, traversalTimeSeconds: Float) =>
        generalizedTraversalCost(edge, legDurationSeconds, traversalTimeSeconds)
    } else if (!appliesMonetaryCosts) { (edge: EdgeStore#Edge, _: Int, traversalTimeSeconds: Float) =>
      traversalTimeSeconds * roadRestrictionMultiplier(edge)
    } else { (edge: EdgeStore#Edge, legDurationSeconds: Int, traversalTimeSeconds: Float) =>
      generalizedTraversalCost(edge, legDurationSeconds, traversalTimeSeconds) * roadRestrictionMultiplier(edge)
    }
  }
}

object R5Wrapper extends StrictLogging {

  private final class DriveTransitRequestDiagnostics(enabled: Boolean) {
    private[this] var mcRaptorExceptions: Int = 0
    private[this] var transitPathsCount: Int = 0

    private[this] val transitPathsByAccessMode: mutable.Map[LegMode, Int] =
      if (enabled) mutable.Map.empty[LegMode, Int] else null

    private[this] val accessModesAttempted: mutable.Set[LegMode] =
      if (enabled) mutable.Set.empty[LegMode] else null

    private[this] val accessModesWithStops: mutable.Set[LegMode] =
      if (enabled) mutable.Set.empty[LegMode] else null

    private[this] val egressModesWithStops: mutable.Set[LegMode] =
      if (enabled) mutable.Set.empty[LegMode] else null

    private[this] val accessSetOriginFailuresByMode: mutable.Map[LegMode, String] =
      if (enabled) mutable.Map.empty[LegMode, String] else null

    private[this] val accessStopSearchVisitedVerticesByMode: mutable.Map[LegMode, Int] =
      if (enabled) mutable.Map.empty[LegMode, Int] else null

    private[this] val accessSnapModeByMode: mutable.Map[LegMode, String] =
      if (enabled) mutable.Map.empty[LegMode, String] else null

    @inline def recordAccessSnapModeIfTransit(legMode: LegMode, hasTransit: Boolean, accessSnapMode: String): Unit =
      if (enabled && hasTransit) {
        accessSnapModeByMode.put(legMode, accessSnapMode)
      }

    @inline def recordAccessModeAttemptedIfTransit(legMode: LegMode, hasTransit: Boolean): Unit =
      if (enabled && hasTransit) {
        accessModesAttempted += legMode
      }

    @inline def recordAccessStopSearch(legMode: LegMode, stopVisitor: StopVisitor): Unit =
      if (enabled) {
        accessStopSearchVisitedVerticesByMode.put(legMode, stopVisitor.getVisitedVertices)
        if (stopVisitor.stops.size() > 0) {
          accessModesWithStops += legMode
        }
      }

    @inline def recordAccessSetOriginFailure(legMode: LegMode, failureDetails: String): Unit =
      if (enabled) {
        accessSetOriginFailuresByMode.put(legMode, failureDetails)
      }

    @inline def recordEgressStopSearch(legMode: LegMode, stopVisitor: StopVisitor): Unit =
      if (enabled && stopVisitor.stops.size() > 0) {
        egressModesWithStops += legMode
      }

    @inline def incrementMcRaptorExceptions(): Unit =
      if (enabled) {
        mcRaptorExceptions += 1
      }

    @inline def recordTransitPathsByAccessMode(mode: LegMode, count: Int): Unit =
      if (enabled) {
        transitPathsByAccessMode.put(mode, count)
      }

    @inline def recordTransitPathsCount(count: Int): Unit =
      if (enabled) {
        transitPathsCount = count
      }

    def recordRouteOutcome(
      requestId: Int,
      accessVehiclesToRouteCount: Int,
      embodiedTrips: IndexedSeq[EmbodiedBeamTrip]
    ): Unit = {
      if (!enabled) return

      val driveTransitTripsCount = embodiedTrips.count(_.tripClassifier == DRIVE_TRANSIT)
      recordDriveTransitFailureOutcome(
        requestId = requestId,
        accessVehiclesToRouteCount = accessVehiclesToRouteCount,
        accessModesAttempted = accessModesAttempted.toSet,
        accessModesWithStops = accessModesWithStops.toSet,
        accessSetOriginFailuresByMode = accessSetOriginFailuresByMode.toMap,
        accessStopSearchVisitedVerticesByMode = accessStopSearchVisitedVerticesByMode.toMap,
        accessSnapModeByMode = accessSnapModeByMode.toMap,
        egressModesWithStops = egressModesWithStops.toSet,
        transitPathsByAccessMode = transitPathsByAccessMode.toMap,
        transitPathsCount = transitPathsCount,
        embodiedTripsCount = embodiedTrips.size,
        driveTransitTripsCount = driveTransitTripsCount,
        mcRaptorExceptions = mcRaptorExceptions
      )
    }
  }

  private def intEnv(name: String, default: Int): Int =
    sys.env.get(name).flatMap(v => Try(v.toInt).toOption).getOrElse(default)

  val enableDriveTransitFailureDiagnostics: Boolean =
    sys.env.get("SINGLEMODE_ROUTING_DIAGNOSTICS").exists(_.equalsIgnoreCase("true"))

  val disableDestinationSplitBreakForDriveTransitAccess: Boolean =
    sys.env
      .get("SINGLEMODE_R5_DISABLE_DESTINATION_SPLIT_BREAK_FOR_DRIVE_ACCESS")
      .exists(_.equalsIgnoreCase("true"))

  val driveTransitAccessDestinationSplitExtraTimeSecondsAfterHit: Int =
    intEnv("SINGLEMODE_R5_DRIVE_ACCESS_DEST_SPLIT_EXTRA_SECONDS_AFTER_HIT", 300)

  val driveTransitAccessDestinationSplitContinueIfStopsBelow: Int =
    intEnv("SINGLEMODE_R5_DRIVE_ACCESS_DEST_SPLIT_CONTINUE_IF_STOPS_BELOW", 1)

  private val driveTransitRequests = new AtomicLong(0L)
  private val driveTransitSuccessWithDriveItinerary = new AtomicLong(0L)
  private val driveTransitNoAccessVehicle = new AtomicLong(0L)
  private val driveTransitNoAccessStops = new AtomicLong(0L)
  private val driveTransitNoEgressStops = new AtomicLong(0L)
  private val driveTransitNoTransitPaths = new AtomicLong(0L)
  private val driveTransitNoEmbodiedTripsAfterTransitPaths = new AtomicLong(0L)
  private val driveTransitNonDriveItinerariesOnly = new AtomicLong(0L)
  private val driveTransitUnknown = new AtomicLong(0L)
  private val driveTransitMcRaptorExceptions = new AtomicLong(0L)
  private val driveTransitNoAccessStopsWithSetOriginFailure = new AtomicLong(0L)
  private val noAccessStopsDetailedLogsEmitted = new AtomicLong(0L)

  private val maxNoAccessStopsDetailedLogs: Long =
    sys.env.get("SINGLEMODE_R5_NO_ACCESS_STOPS_DETAILED_LOGS").flatMap(v => Try(v.toLong).toOption).getOrElse(25L)

  def driveTransitDiagnosticsLine: String =
    s"[R5-DRIVE-TRANSIT-DIAGNOSTICS] requests=${driveTransitRequests.get()} " +
    s"successWithDriveItinerary=${driveTransitSuccessWithDriveItinerary.get()} " +
    s"noAccessVehicle=${driveTransitNoAccessVehicle.get()} " +
    s"noAccessStops=${driveTransitNoAccessStops.get()} " +
    s"noEgressStops=${driveTransitNoEgressStops.get()} " +
    s"noTransitPaths=${driveTransitNoTransitPaths.get()} " +
    s"noEmbodiedTripsAfterTransitPaths=${driveTransitNoEmbodiedTripsAfterTransitPaths.get()} " +
    s"nonDriveItinerariesOnly=${driveTransitNonDriveItinerariesOnly.get()} " +
    s"unknown=${driveTransitUnknown.get()} " +
    s"mcRaptorExceptions=${driveTransitMcRaptorExceptions.get()} " +
    s"noAccessStopsWithSetOriginFailure=${driveTransitNoAccessStopsWithSetOriginFailure.get()} " +
    s"destSplitExtraTimeSecondsAfterHit=$driveTransitAccessDestinationSplitExtraTimeSecondsAfterHit " +
    s"destSplitContinueIfStopsBelow=$driveTransitAccessDestinationSplitContinueIfStopsBelow " +
    s"disableDestSplitBreak=$disableDestinationSplitBreakForDriveTransitAccess"

  def recordDriveTransitFailureOutcome(
    requestId: Int,
    accessVehiclesToRouteCount: Int,
    accessModesAttempted: Set[LegMode],
    accessModesWithStops: Set[LegMode],
    accessSetOriginFailuresByMode: Map[LegMode, String],
    accessStopSearchVisitedVerticesByMode: Map[LegMode, Int],
    accessSnapModeByMode: Map[LegMode, String],
    egressModesWithStops: Set[LegMode],
    transitPathsByAccessMode: Map[LegMode, Int],
    transitPathsCount: Int,
    embodiedTripsCount: Int,
    driveTransitTripsCount: Int,
    mcRaptorExceptions: Int
  ): Unit = {
    val totalRequests = driveTransitRequests.incrementAndGet()
    if (mcRaptorExceptions > 0) {
      driveTransitMcRaptorExceptions.addAndGet(mcRaptorExceptions.toLong)
    }
    val outcome =
      if (driveTransitTripsCount > 0) {
        driveTransitSuccessWithDriveItinerary.incrementAndGet()
        "successWithDriveItinerary"
      } else if (accessVehiclesToRouteCount == 0) {
        driveTransitNoAccessVehicle.incrementAndGet()
        "noAccessVehicle"
      } else if (accessModesWithStops.isEmpty) {
        driveTransitNoAccessStops.incrementAndGet()
        if (accessSetOriginFailuresByMode.nonEmpty) {
          driveTransitNoAccessStopsWithSetOriginFailure.incrementAndGet()
        }
        "noAccessStops"
      } else if (egressModesWithStops.isEmpty) {
        driveTransitNoEgressStops.incrementAndGet()
        "noEgressStops"
      } else if (transitPathsCount == 0) {
        driveTransitNoTransitPaths.incrementAndGet()
        "noTransitPaths"
      } else if (embodiedTripsCount == 0) {
        driveTransitNoEmbodiedTripsAfterTransitPaths.incrementAndGet()
        "noEmbodiedTripsAfterTransitPaths"
      } else {
        driveTransitNonDriveItinerariesOnly.incrementAndGet()
        "nonDriveItinerariesOnly"
      }

    if (outcome == "noAccessStops") {
      val emitted = noAccessStopsDetailedLogsEmitted.incrementAndGet()
      if (emitted <= maxNoAccessStopsDetailedLogs || emitted % 500 == 0) {
        logger.info(
          s"[R5-DRIVE-TRANSIT-NO-ACCESS-DETAIL] requestId=$requestId, " +
          s"accessModesAttempted=${accessModesAttempted.mkString("[", ",", "]")}, " +
          s"accessSnapModeByMode=${accessSnapModeByMode.mkString("{", ",", "}")}, " +
          s"accessSetOriginFailuresByMode=${accessSetOriginFailuresByMode.mkString("{", ",", "}")}, " +
          s"accessStopSearchVisitedVerticesByMode=${accessStopSearchVisitedVerticesByMode.mkString("{", ",", "}")}, " +
          s"transitPathsByAccessMode=${transitPathsByAccessMode.mkString("{", ",", "}")}"
        )
      }
    } else if (logger.underlying.isDebugEnabled && outcome != "successWithDriveItinerary") {
      logger.debug(
        s"[R5-DRIVE-TRANSIT-REQUEST] requestId=$requestId, outcome=$outcome, " +
        s"accessVehiclesToRoute=$accessVehiclesToRouteCount, accessModesAttempted=${accessModesAttempted
          .mkString("[", ",", "]")}, " +
        s"accessModesWithStops=${accessModesWithStops.mkString("[", ",", "]")}, " +
        s"accessSetOriginFailuresByMode=${accessSetOriginFailuresByMode.mkString("{", ",", "}")}, " +
        s"accessStopSearchVisitedVerticesByMode=${accessStopSearchVisitedVerticesByMode.mkString("{", ",", "}")}, " +
        s"egressModesWithStops=${egressModesWithStops.mkString("[", ",", "]")}, " +
        s"transitPathsByAccessMode=${transitPathsByAccessMode.mkString("{", ",", "}")}, " +
        s"transitPathsCount=$transitPathsCount, embodiedTripsCount=$embodiedTripsCount, " +
        s"driveTransitTripsCount=$driveTransitTripsCount, mcRaptorExceptions=$mcRaptorExceptions"
      )
    }

    if (totalRequests % 200 == 0) {
      logger.info(driveTransitDiagnosticsLine)
    }
  }

  // Road restrictions for heavy- and medium- duty vehicles are defined as following
  // mdvBannedByWeight = (weightInTons & (numericWeight <= 3.0)) | (weightInLbs & (numericWeight <= 6000))
  // hdvBannedByWeight = (weightInTons & (numericWeight <= 7.0)) | (weightInLbs & (numericWeight <= 14000))
  // hgvAllowedByDefault = edges.hgv.str.lower() != "no"
  // longVehiclesBanned = ~edges.maxlength.isna()
  // hgv = hgvAllowedByDefault & ~hdvBannedByWeight & ~longVehiclesBanned
  // mdv = hgvAllowedByDefault & ~mdvBannedByWeight
  // More info from March, 2024: https://github.com/zneedell/osmnx/blob/numeric-lanes/scratch/downloadSfBay.py
  private val HeavyHeavyDutyTruckTag = "hgv"
  private val LightAndMediumHeavyDutyTruckTag = "mdv"

  sealed trait RoutingVehicleCategory

  private object RoutingVehicleCategory {
    case object HeavyDuty extends RoutingVehicleCategory
    case object MediumDuty extends RoutingVehicleCategory
    case object Other extends RoutingVehicleCategory

    val values: Set[RoutingVehicleCategory] = Set(HeavyDuty, MediumDuty, Other)

    def fromCategory(category: VehicleCategory.VehicleCategory): RoutingVehicleCategory = category match {
      case VehicleCategory.Class78Tractor | VehicleCategory.Class78Vocational => HeavyDuty
      case VehicleCategory.Class456Vocational                                 => MediumDuty
      case _                                                                  => Other
    }
  }

  private case class RoadRestrictions(hhdt: Boolean, lmhdt: Boolean, freeSpeed: Double) {

    def isRestricted(category: RoutingVehicleCategory, speedThreshold: Double): Boolean = {
      category match {
        case RoutingVehicleCategory.HeavyDuty  => !hhdt
        case RoutingVehicleCategory.MediumDuty => !lmhdt
        case RoutingVehicleCategory.Other      => freeSpeed > speedThreshold
      }
    }

    def isRestricted(category: VehicleCategory.VehicleCategory, speedThreshold: Double): Boolean = {
      isRestricted(RoutingVehicleCategory.fromCategory(category), speedThreshold)
    }
  }
}
