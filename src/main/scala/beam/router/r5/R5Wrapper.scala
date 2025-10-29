package beam.router.r5

import beam.agentsim.agents.choice.mode.DrivingCost
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
import java.util.function.IntFunction
import java.util.{Collections, Optional}
import scala.collection.JavaConverters._
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

class R5Wrapper(workerParams: R5Parameters, travelTime: TravelTime, travelTimeNoiseFraction: Double)
    extends MetricsSupport
    with StrictLogging
    with Router {
  import R5Wrapper._

  private val maxDistanceForBikeMeters: Int =
    workerParams.beamConfig.beam.routing.r5.maxDistanceLimitByModeInMeters.bike

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

  private val statePoolSize: Int = 200000 // beamConfig.beam.routing.r5.statePoolSize
  private val accessEgressStatePoolSize: Int = 100000 // beamConfig.beam.routing.r5.accessEgressStatePoolSize

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

  private val statePoolCapacities: ThreadLocal[java.util.Map[StatePool, Int]] =
    ThreadLocal.withInitial(() => new java.util.HashMap()) // Hack to avoid changing R5 again

  private def getMcRaptorPoolSize(streetMode: StreetMode, listSupplierType: String): Int = {
    (streetMode, listSupplierType) match {
      case (StreetMode.WALK, "suboptimal")          => 2000000 // ← Was 100k, need at least 200k
      case (StreetMode.WALK, "beam")                => 500000
      case (StreetMode.CAR | StreetMode.BICYCLE, _) => 20000 // ← Was 100k, way too big
      case _                                        => 100000
    }
  }

  private val transferSegmentCache = mutable.Map.empty[(Int, Int), StreetSegment]

  private val mcRaptorStatePools: ThreadLocal[mutable.Map[StreetMode, McRaptorStatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[StreetMode, McRaptorStatePool])

  private val mcRaptorRouterCaches
    : ThreadLocal[mutable.Map[McRaptorRouterCacheKey, util.ArrayDeque[McRaptorSuboptimalPathProfileRouter]]] =
    ThreadLocal.withInitial(() =>
      mutable.Map.empty[McRaptorRouterCacheKey, util.ArrayDeque[McRaptorSuboptimalPathProfileRouter]]
    )

  private val mcRaptorPoolCapacities: ThreadLocal[java.util.Map[McRaptorStatePool, Int]] =
    ThreadLocal.withInitial(() => new java.util.HashMap())

  private def borrowMcRaptorRouter(
    streetMode: StreetMode,
    profileRequest: ProfileRequest,
    accessTimes: java.util.Map[LegMode, gnu.trove.map.TIntIntMap],
    egressTimes: java.util.Map[LegMode, gnu.trove.map.TIntIntMap],
    departureTimeToDominatingList: IntFunction[DominatingList],
    collapseParetoSurfaceToTime: InRoutingFareCalculator.Collater,
    isDriveTransitRequest: Boolean
  ): McRaptorSuboptimalPathProfileRouter = {

    val listSupplierType =
      if (isDriveTransitRequest) "beam"
      else {
        beamConfig.beam.routing.r5.transitAlternativeList.toLowerCase match {
          case "suboptimal" => "suboptimal"
          case _            => "beam"
        }
      }

    val cacheKey = McRaptorRouterCacheKey(streetMode, listSupplierType)

    // Get or create SHARED state pool for this mode
    // All "WALK + suboptimal" McRaptors share one pool
    val poolSize = getMcRaptorPoolSize(streetMode, listSupplierType)
    val statePool = mcRaptorStatePools
      .get()
      .getOrElseUpdate(
        streetMode, {
          val pool = new McRaptorStatePool(poolSize)
          mcRaptorPoolCapacities.get().put(pool, poolSize)
          logger
            .info(s"[MCRAPTOR-POOL-CREATE] mode=$streetMode, type=$listSupplierType, poolSize=$poolSize")
          pool
        }
      )

    // Get or create router cache
    val routerCache =
      mcRaptorRouterCaches.get().getOrElseUpdate(cacheKey, new util.ArrayDeque[McRaptorSuboptimalPathProfileRouter](5))

    val stats = mcRaptorPoolStatsMap
      .get()
      .computeIfAbsent(
        cacheKey,
        _ =>
          McRaptorPoolStats(
            streetMode = streetMode,
            listSupplierType = listSupplierType,
            poolCapacity = getMcRaptorPoolSize(streetMode, listSupplierType)
          )
      )

    val router = if (routerCache.isEmpty) {
      stats.routerMisses += 1
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
      stats.routerHits += 1
      routerCache.poll()
    }

    // Reset the router for reuse
    router.reset(profileRequest, accessTimes, egressTimes, departureTimeToDominatingList, collapseParetoSurfaceToTime)

    router
  }

  private def returnMcRaptorRouter(
    router: McRaptorSuboptimalPathProfileRouter,
    r5mode: StreetMode,
    isDriveTransitRequest: Boolean
  ): Unit = {
    val listSupplierType =
      if (isDriveTransitRequest) "beam"
      else {
        beamConfig.beam.routing.r5.transitAlternativeList.toLowerCase match {
          case "suboptimal" => "suboptimal"
          case _            => "beam"
        }
      }

    val cacheKey = McRaptorRouterCacheKey(r5mode, listSupplierType)
    val routerCache =
      mcRaptorRouterCaches.get().getOrElseUpdate(cacheKey, new util.ArrayDeque[McRaptorSuboptimalPathProfileRouter](5))

    val stats = mcRaptorPoolStatsMap.get().get(cacheKey)

    // Track state pool usage
    val exhaustions = router.getStatePoolExhaustionsSinceReset
    val maxInUse = router.getStatePoolMaxInUse

    stats.totalRoutes += 1
    stats.maxStatesInUse = Math.max(stats.maxStatesInUse, maxInUse)

    if (exhaustions > 0) {
      stats.routesWithExhaustion += 1
      stats.totalExtraStates += exhaustions
      stats.maxExtraInOneRoute = Math.max(stats.maxExtraInOneRoute, exhaustions)
    }

    // Log periodically
    if (stats.totalRoutes % 500 == 0) {
      logMcRaptorPoolStats(stats)
    }

    // Return router to cache
    if (routerCache.size() < 5) {
      routerCache.offer(router)
      stats.routerReturns += 1
      stats.maxCacheSize = Math.max(stats.maxCacheSize, routerCache.size())
    } else {
      stats.routerDiscards += 1
    }
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

    val statsMap = poolStatsMap.get()
    val capacity = statePoolCapacities.get().getOrDefault(statePool, 0)
    val stats = statsMap.computeIfAbsent(
      statePool,
      _ =>
        StatePoolStats(
          poolType = determinePoolType(statePool),
          poolCapacity = capacity
        )
    )

    val router = if (cache.isEmpty) {
      stats.routerMisses += 1
      val poolType = determinePoolType(statePool)
      val threadId = Thread.currentThread.getId
      val totalCaches = cacheMap.size
      val totalRouters = cacheMap.values.map(_.size()).sum

      logger.warn(
        s"[CACHE-MISS] Creating new StreetRouter | " +
        s"thread=$threadId, " +
        s"pool=$poolType, " +
        s"quantityToMinimize=$quantityToMinimize, " +
        s"cache_empty=${cache.isEmpty}, " +
        s"total_caches=$totalCaches, " +
        s"total_routers_cached=$totalRouters, " +
        s"miss_count=${stats.routerMisses}, " +
        s"hit_count=${stats.routerHits}"
      )

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
      stats.routerHits += 1
      cache.poll()
    }

    router.reset() // This won't change the comparator
    // quantityToMinimize is already correct for this cache
    router
  }

  private def returnRouterWithStatePool(
    router: StreetRouter,
    statePool: StatePool,
    quantityToMinimize: StreetRouter.State.RoutingVariable
  ): Unit = {
    val cacheKey = RouterCacheKey(statePool, quantityToMinimize)
    val cacheMap = routerCaches.get()
    val cache = cacheMap.getOrElseUpdate(cacheKey, new util.ArrayDeque[StreetRouter](50))
    val statsMap = poolStatsMap.get()
    val sizeBefore = cache.size()

    val capacity = statePoolCapacities.get().getOrDefault(statePool, 0)
    val stats = statsMap.computeIfAbsent(
      statePool,
      _ =>
        StatePoolStats(
          poolType = determinePoolType(statePool),
          poolCapacity = capacity
        )
    )

    // Track state pool usage/exhaustion
    val extraStates = router.getStatePoolExhaustionsSinceReset
    val maxInUse = router.getStatePoolMaxInUse

    stats.totalRoutes += 1
    stats.maxStatesInUse = Math.max(stats.maxStatesInUse, maxInUse)

    if (extraStates > 0) {
      stats.routesWithExhaustion += 1
      stats.totalExtraStates += extraStates
      stats.maxExtraInOneRoute = Math.max(stats.maxExtraInOneRoute, extraStates)
    }

    // Log stats periodically
    if (stats.totalRoutes % 5000 == 0) {
      logPoolStats(stats)
    }

    // Return router to cache
    if (cache.size() < 50) {
      cache.offer(router)
      logger.debug(
        s"[ROUTER-RETURNED] thread=${Thread.currentThread.getId}, " +
        s"pool=${determinePoolType(statePool)}, " +
        s"cache_before=$sizeBefore, cache_after=${cache.size()}"
      )
      stats.routerReturns += 1
      stats.maxCacheSize = Math.max(stats.maxCacheSize, cache.size())
    } else {
      stats.routerDiscards += 1
    }
  }

  private val transitAccessStatePools: ThreadLocal[mutable.Map[StreetMode, StatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[StreetMode, StatePool])

  private val transitEgressStatePools: ThreadLocal[mutable.Map[StreetMode, StatePool]] =
    ThreadLocal.withInitial(() => mutable.Map.empty[StreetMode, StatePool])

  /** Stats for a specific StatePool (tracks both router pooling and state exhaustions) */
  private case class StatePoolStats(
    poolType: String, // "MAIN", "ACCESS[WALK]", etc.
    poolCapacity: Int, // From StatePool.getCapacity
    var routerHits: Long = 0, // Router borrowed from cache
    var routerMisses: Long = 0, // Router created new
    var routerReturns: Long = 0, // Router returned to cache
    var routerDiscards: Long = 0, // Router discarded (cache full)
    var maxCacheSize: Int = 0, // Largest router cache size
    var totalRoutes: Long = 0, // Routes processed with this pool
    var routesWithExhaustion: Long = 0, // Routes that exhausted the StatePool
    var totalExtraStates: Long = 0, // Total extra states allocated
    var maxExtraInOneRoute: Int = 0, // Worst single route
    var maxStatesInUse: Int = 0 // Peak concurrent usage
  )

  /** Per-thread map: StatePool -> its stats */
  private val poolStatsMap: ThreadLocal[java.util.Map[StatePool, StatePoolStats]] =
    ThreadLocal.withInitial(() => new java.util.HashMap())

  private case class McRaptorPoolStats(
    streetMode: StreetMode,
    listSupplierType: String,
    poolCapacity: Int,
    var routerHits: Long = 0,
    var routerMisses: Long = 0,
    var routerReturns: Long = 0,
    var routerDiscards: Long = 0,
    var maxCacheSize: Int = 0,
    var totalRoutes: Long = 0,
    var routesWithExhaustion: Long = 0,
    var totalExtraStates: Long = 0,
    var maxExtraInOneRoute: Int = 0,
    var maxStatesInUse: Int = 0
  )

  // Separate map for McRaptor stats
  private val mcRaptorPoolStatsMap: ThreadLocal[java.util.Map[McRaptorRouterCacheKey, McRaptorPoolStats]] =
    ThreadLocal.withInitial(() => new java.util.HashMap())

  private def returnRouter(router: StreetRouter): Unit = {
    // Delegate to the unified method with mainRoutingPool
    returnRouterWithStatePool(router, mainRoutingPool.get(), StreetRouter.State.RoutingVariable.WEIGHT)
  }

  private val mainRoutingPool: ThreadLocal[StatePool] =
    ThreadLocal.withInitial(() => {
      val pool = new StatePool(statePoolSize)
      statePoolCapacities.get().put(pool, statePoolSize) // Track capacity
      pool
    })

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

  private def determinePoolType(statePool: StatePool): String = {
    if (statePool == mainRoutingPool.get()) {
      "MAIN"
    } else {
      transitAccessStatePools
        .get()
        .find(_._2 == statePool)
        .map(kv => s"ACCESS[${kv._1}]")
        .orElse(transitEgressStatePools.get().find(_._2 == statePool).map(kv => s"EGRESS[${kv._1}]"))
        .getOrElse("UNKNOWN")
    }
  }

  // ============================================================================
  // UNIFIED LOGGING
  // ============================================================================

  private def logMcRaptorPoolStats(stats: McRaptorPoolStats): Unit = {
    val exhaustionRate = if (stats.totalRoutes > 0) {
      (stats.routesWithExhaustion * 100.0) / stats.totalRoutes
    } else 0.0

    val routerCacheEfficiency = if (stats.routerHits + stats.routerMisses > 0) {
      (stats.routerHits * 100.0) / (stats.routerHits + stats.routerMisses)
    } else 0.0

    // Cap utilization at 100% for display, but show if it exceeded
    val utilizationPct = if (stats.poolCapacity > 0) {
      Math.min(100.0, (stats.maxStatesInUse * 100.0) / stats.poolCapacity)
    } else 0.0

    val exceededBy = Math.max(0, stats.maxStatesInUse - stats.poolCapacity)
    val exceededMsg = if (exceededBy > 0) s", EXCEEDED by $exceededBy" else ""

    logger.info(
      s"McRaptorStatePool [${stats.streetMode}/${stats.listSupplierType}] @ thread ${Thread.currentThread.getId}: " +
      s"capacity=${stats.poolCapacity}, " +
      s"routes=${stats.totalRoutes}, " +
      s"peak_usage=${stats.maxStatesInUse} (${f"$utilizationPct%.0f"}%%$exceededMsg), " +
      s"exhausted=${stats.routesWithExhaustion} (${f"$exhaustionRate%.1f"}%), " +
      s"router_cache_hits=${f"$routerCacheEfficiency%.1f"}%%"
    )
  }

  private def logPoolStats(stats: StatePoolStats): Unit = {
    val exhaustionRate = if (stats.totalRoutes > 0) {
      (stats.routesWithExhaustion * 100.0) / stats.totalRoutes
    } else 0.0

    val avgExtraPerExhausted = if (stats.routesWithExhaustion > 0) {
      stats.totalExtraStates / stats.routesWithExhaustion
    } else 0

    val garbageGB = (stats.totalExtraStates * 500.0) / (1024 * 1024 * 1024)

    val routerCacheEfficiency = if (stats.routerHits + stats.routerMisses > 0) {
      (stats.routerHits * 100.0) / (stats.routerHits + stats.routerMisses)
    } else 0.0

    val utilizationPct = if (stats.poolCapacity > 0) {
      (stats.maxStatesInUse * 100.0) / stats.poolCapacity
    } else 0.0

    logger.info(
      s"StatePool [${stats.poolType}] @ thread ${Thread.currentThread.getId}: " +
      s"capacity=${stats.poolCapacity}, " +
      s"routes=${stats.totalRoutes}, " +
      s"peak_usage=${stats.maxStatesInUse} (${f"$utilizationPct%.0f"}%%), " +
      s"exhausted=${stats.routesWithExhaustion} (${f"$exhaustionRate%.1f"}%), " +
      s"total_extra=${stats.totalExtraStates}, " +
      s"avg_extra=$avgExtraPerExhausted, " +
      s"max_extra=${stats.maxExtraInOneRoute}, " +
      s"garbage=${f"$garbageGB%.2f"}GB, " +
      s"router_cache_hits=${f"$routerCacheEfficiency%.1f"}%% " +
      s"(${stats.routerHits}/${stats.routerHits + stats.routerMisses}) " +
      s"discards=${stats.routerDiscards}"
    )

    // Warnings for exhaustion issues
    if (exhaustionRate > 20) {
      logger.warn(
        s"[${stats.poolType}] HIGH exhaustion rate (${f"$exhaustionRate%.1f"}%)! " +
        s"Consider increasing pool from ${stats.poolCapacity} to ${stats.poolCapacity * 5}"
      )
    } else if (exhaustionRate > 10) {
      logger.warn(
        s"[${stats.poolType}] Moderate exhaustion rate (${f"$exhaustionRate%.1f"}%). " +
        s"Consider increasing pool from ${stats.poolCapacity} to ${stats.poolCapacity * 2}"
      )
    }

    if (stats.maxExtraInOneRoute > stats.poolCapacity) {
      logger.warn(
        s"[${stats.poolType}] Worst route needed ${stats.maxExtraInOneRoute} extra states! " +
        s"Consider pool size of ${(stats.maxExtraInOneRoute * 1.2).toInt}"
      )
    }

    // Warnings for router cache issues
    if (routerCacheEfficiency < 80 && stats.totalRoutes > 1000) {
      logger.warn(
        s"[${stats.poolType}] Low router cache hit rate (${f"$routerCacheEfficiency%.1f"}%). " +
        s"Router pooling may not be working correctly."
      )
    }
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

  private def getStreetPlanFromR5(request: R5Request): ProfileResponse = {
    countOccurrence("r5-plans-count", request.time)
    val vehicleType = vehicleTypes(request.beamVehicleTypeId)
    val profileRequest = createProfileRequestFromRequest(request)
    try {
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (mode <- profileRequest.directModes.asScala) {
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
    profileRequest.streetTime = 6 * 60
    profileRequest.maxTripDurationMinutes = 6 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    // profileRequest.monteCarloDraws = beamConfig.beam.routing.r5.numberOfSamples
    profileRequest.monteCarloDraws = 1
    profileRequest.date = dates.localBaseDate
    // Doesn't calculate any fares, is just a no-op placeholder
    profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator
    profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutes
    profileRequest
  }

  def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    val routeCalcStarted = System.currentTimeMillis()
    val accessRoutersToReturn = mutable.ArrayBuffer[(StreetRouter, StatePool, StreetRouter.State.RoutingVariable)]()
    val egressRoutersToReturn = mutable.ArrayBuffer[(StreetRouter, StatePool, StreetRouter.State.RoutingVariable)]()

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
                  )
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
              )
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

      val maybeWalkToVehicle: Map[StreetVehicle, Option[EmbodiedBeamLeg]] =
        accessVehicles.map(v => v -> calcRouteToVehicle(v)).toMap

      @SuppressWarnings(Array("UnsafeTraversableMethods"))
      val bestAccessVehiclesByR5Mode: Map[LegMode, StreetVehicle] = accessVehicles
        .groupBy(_.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf("")))
        .mapValues(vehicles => vehicles.minBy(maybeWalkToVehicle(_).map(leg => leg.beamLeg.duration).getOrElse(0)))

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
      val destinationVehicles = if (mainRouteToVehicle) {
        request.streetVehicles.filter(_.mode != WALK)
      } else {
        Vector()
      }
      if (request.withTransit) {
        profileRequest.transitModes = util.EnumSet.allOf(classOf[TransitModes])
      }

      val destinationVehicle = destinationVehicles.headOption
      val vehicleToDestinationLeg = destinationVehicle.map(v => routeFromVehicleToDestination(v))

      val accessRouters = mutable.Map[LegMode, StreetRouter]()
      val accessStopsByMode = mutable.Map[LegMode, StopVisitor]()
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (vehicle <- bestAccessVehiclesByR5Mode.values) {
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
        val from = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(theOrigin),
          linkRadiusMeters
        )
        val to = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(theDestination),
          linkRadiusMeters
        )
        profileRequest.fromLon = from.getX
        profileRequest.fromLat = from.getY
        profileRequest.toLon = to.getX
        profileRequest.toLat = to.getY

        val walkToVehicleDuration = maybeWalkToVehicle(vehicle).map(leg => leg.beamLeg.duration).getOrElse(0)
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
              val pool = new StatePool(accessEgressStatePoolSize) // Created once per thread per mode
              logger.debug(s"[POOL-CREATE] NEW access pool mode=$r5mode poolId=${System.identityHashCode(pool)}")
              statePoolCapacities.get().put(pool, accessEgressStatePoolSize) // Track capacity
              pool
            }
          )
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
          StreetRouter.State.RoutingVariable.DURATION_SECONDS
        )
        accessRoutersToReturn += (
          (
            streetRouter,
            accessStatePool,
            StreetRouter.State.RoutingVariable.DURATION_SECONDS
          )
        )

        if (vehicle.mode == BeamMode.BIKE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        streetRouter.profileRequest = profileRequest
        streetRouter.streetMode = toR5StreetMode(vehicle.mode)
        val legMode: LegMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
        val calcDirectRoute = legMode match {
          case LegMode.WALK => buildDirectWalkRoute
          case LegMode.CAR  => buildDirectCarRoute
          case _            => true
        }
        if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon, linkRadiusMeters)) {
          if (profileRequest.hasTransit) {
            val destinationSplit = transportNetwork.streetLayer.findSplit(
              profileRequest.toLat,
              profileRequest.toLon,
              linkRadiusMeters,
              streetRouter.streetMode
            )
            val stopVisitor = new StopVisitor(
              transportNetwork.streetLayer,
              streetRouter.quantityToMinimize,
              streetRouter.transitStopSearchQuantity,
              profileRequest.getMinTimeSeconds(streetRouter.streetMode),
              destinationSplit
            )
            streetRouter.setRoutingVisitor(stopVisitor)
            streetRouter.timeLimitSeconds = profileRequest.getMaxTimeSeconds(legMode)
            streetRouter.route()

            accessRouters.put(legMode, streetRouter) // For R5 API (keeps last per mode)
            accessStopsByMode.put(legMode, stopVisitor)
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
        }
      }

      directOption.summary = directOption.generateSummary
      profileResponse.addOption(directOption)

      if (profileRequest.hasTransit) {
        val egressRouters = mutable.Map[LegMode, StreetRouter]()
        val egressStopsByMode = mutable.Map[LegMode, StopVisitor]()
        profileRequest.reverseSearch = true
        val isCarEgress = egressVehicles.exists(_.mode == CAR)
        for (vehicle <- egressVehicles) {
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
                val pool = new StatePool(accessEgressStatePoolSize) // Created once per thread per mode
                logger.debug(s"[POOL-CREATE] NEW egress pool mode=$r5mode poolId=${System.identityHashCode(pool)}")
                statePoolCapacities.get().put(pool, accessEgressStatePoolSize) // Track capacity
                pool
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
            destinationSplit
          )
          streetRouter.setRoutingVisitor(stopVisitor)
          if (streetRouter.setOrigin(profileRequest.toLat, profileRequest.toLon, linkRadiusMeters)) {
            streetRouter.route()
            egressRouters.put(legMode, streetRouter)
            egressStopsByMode.put(legMode, stopVisitor)
          }
        }

        val transitPaths = latency("getpath-transit-time", Metrics.VerboseLevel) {
          accessStopsByMode.flatMap { case (mode, stopVisitor) =>
            val isDriveTransitRequest = mode == LegMode.CAR || mode == LegMode.BICYCLE ||
              egressVehicles.exists(v => Seq(CAR, BIKE).contains(v.mode))

            if (isDriveTransitRequest) {
              profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutesForDriveAccess
              profileRequest.maxRides = 2
              profileRequest.maxTripDurationMinutes = 120
            } else {
              profileRequest.suboptimalMinutes = beamConfig.beam.routing.r5.suboptimalMinutes
              profileRequest.maxRides = 3
              profileRequest.maxTripDurationMinutes = 180
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
              case _ if egressVehicles.exists(v => Seq(CAR, BIKE).contains(v.mode)) =>
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

            val accessModesSet = util.EnumSet.noneOf(classOf[LegMode])
            accessStopsByMode.keys.foreach(mode => accessModesSet.add(mode))
            profileRequest.accessModes = accessModesSet

            val egressModesSet = util.EnumSet.noneOf(classOf[LegMode])
            egressStopsByMode.keys.foreach(mode => egressModesSet.add(mode))
            profileRequest.egressModes = egressModesSet

            // Important to allow 61 seconds for transit schedules to be considered! Along with any other buffers
            val isDriveTransitRequestForM = mode == LegMode.CAR || mode == LegMode.BICYCLE ||
              egressVehicles.exists(v => Seq(CAR, BIKE).contains(v.mode))

            val accessTimesJava = new java.util.HashMap[LegMode, TIntIntMap]()
            accessTimesJava.put(mode, stopVisitor.stops)

            val egressTimesJava = new java.util.HashMap[LegMode, TIntIntMap]()
            egressStopsByMode.foreach { case (m, visitor) =>
              egressTimesJava.put(m, visitor.stops)
            }

            val r5mode = Modes.toR5StreetMode(mode)

            val router = borrowMcRaptorRouter(
              r5mode,
              profileRequest,
              accessTimesJava,
              egressTimesJava,
              departureTimeToDominatingList,
              null,
              isDriveTransitRequestForM
            )

            try {
              val paths = Try(router.getPaths.asScala) match {
                case Success(p) => p
                case Failure(e) =>
                  logger.error(s"[MCRAPTOR-EXCEPTION] mode=$mode", e)
                  Nil
              }
              paths
            } finally {
              returnMcRaptorRouter(router, r5mode, isDriveTransitRequest)
            }
          }

          // Catch IllegalStateException in R5.StatsCalculator
        }

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
            //TODO make a more sensible window not just 30 minutes
            trip.legs.forall(l =>
              l.beamLeg.startTime >= request.departureTime
            ) && trip.legs.head.beamLeg.startTime <= request.departureTime + 1800
          }
      }

      val embodiedTrips = deduplicateItineraries(rawEmbodiedTrips.toVector)

      val modesWeSearched =
        searchedModes(request, buildDirectCarRoute, buildDirectWalkRoute, isRouteForPerson, mainRouteRideHailTransit)

      val routingResponse = if (!embodiedTrips.exists(_.tripClassifier == WALK) && !mainRouteToVehicle) {
        val maybeBody = accessVehicles.find(_.mode == WALK)
        if (buildDirectWalkRoute && maybeBody.isDefined) {
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
      val threadId = Thread.currentThread.getId
      logger.debug(
        s"[FINALLY-BLOCK] thread=$threadId, " +
        s"accessToReturn=${accessRoutersToReturn.size}, " +
        s"egressToReturn=${egressRoutersToReturn.size}"
      )
      accessRoutersToReturn.foreach { case (router, pool, qtm) =>
        logger.debug(s"[RETURNING-ACCESS] thread=$threadId, pool=${determinePoolType(pool)}, qtm=$qtm")
        returnRouterWithStatePool(router, pool, qtm)
      }

      egressRoutersToReturn.foreach { case (router, pool, qtm) =>
        logger.debug(s"[RETURNING-EGRESS] thread=$threadId, pool=${determinePoolType(pool)}, qtm=$qtm")
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

    // Group transfers by start stop
    val transfersByStart = transfersToOptions
      .keySet()
      .asScala
      .groupBy(_.getAlightStop)

    val prevReverseSearch = profileRequest.reverseSearch
    profileRequest.reverseSearch = false

    try {
      transfersByStart.foreach { case (alightStopIdx, transfers) =>
        // Borrow router from pool instead of creating new
        val streetRouter = borrowRouterWithStatePool(
          getTravelTimeCalculator(vehicleTypes(walkVehicleTypeId), shouldAddNoise = false),
          travelCostCalculator(vehicleTypes(walkVehicleTypeId), 0, profileRequest.fromTime, 0, 0),
          mainRoutingPool.get(),
          StreetRouter.State.RoutingVariable.DURATION_SECONDS
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

            // Cache key based on origin-destination pair
            val cacheKey = (alightStopIdx.intValue(), transfer.boardStop)
            val streetSegment = transferSegmentCache.getOrElseUpdate(
              cacheKey, {
                // Only create if not cached
                val lastState = streetRouter.getStateAtVertex(endIndex)
                if (lastState != null) {
                  val streetPath = new StreetPath(lastState, transportNetwork, false)
                  new StreetSegment(streetPath, LegMode.WALK, transportNetwork.streetLayer)
                } else {
                  null
                }
              }
            )

            if (streetSegment != null) {
              transfersToOptions.get(transfer).asScala.foreach { profileOption =>
                profileOption.addMiddle(streetSegment, transfer)
              }
            }
          }

        } finally {
          //  Return router to pool
          returnRouterWithStatePool(
            streetRouter,
            mainRoutingPool.get(),
            StreetRouter.State.RoutingVariable.DURATION_SECONDS
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
      trip.legs.filter(_.beamLeg.mode.isTransit).map(_.beamVehicleId).sorted
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

    if (!travelTimeCalculatorCache.get().contains(config)) {
      val threadId = Thread.currentThread.getId
      logger.info(
        s"[TTC-CACHE-MISS] Creating new TravelTimeCalculator | " +
        s"thread=$threadId, " +
        s"mode=${config.streetMode}, " +
        s"maxSpeed=${config.maxSpeedMps.getOrElse("unlimited")}, " +
        s"noise=$shouldAddNoise, " +
        s"bikeScale=$shouldApplyBicycleScaleFactor, " +
        s"cache_size=${travelTimeCalculatorCache.get().size}"
      )
    }

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

    // Cache the maximum velocity for this vehicle type
    val vehicleMaxSpeed = vehicleType.maxVelocity.getOrElse(Double.MaxValue)

    (time: Double, linkId: Int, streetMode: StreetMode) => {
      // Get the edge length, using the cache
      val edgeLength = transportNetwork.streetLayer.edgeStore.lengths_mm.get(linkId / 2) / 1000.0

      // Calculate the mode-specific speed
      val maxSpeed: Double = if (streetMode == StreetMode.CAR) {
        Math.min(vehicleMaxSpeed, profileRequest.getSpeedForMode(streetMode))
      } else {
        profileRequest.getSpeedForMode(streetMode)
      }

      val minTravelTime = edgeLength / maxSpeed

      if (streetMode == StreetMode.BICYCLE && shouldApplyBicycleScaleFactor) {
        //note we're not explicitly checking that it is a Bike VehicleType
        minTravelTime * bikeScaleFactor.scaleFactor(linkId)
      } else if (streetMode == StreetMode.CAR) {
        carWeightCalculator.calcTravelTime(linkId, travelTime, maxSpeed, time, shouldAddNoise, edgeLength)
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
    // Pre-compute ONCE per route (not per edge!)
    val category = RoutingVehicleCategory.fromCategory(vehicleType.vehicleCategory)
    val categoryRestrictions = precomputedRestrictions.getOrElse(category, new TLongByteHashMap())
    val weightMultiplier = workerParams.beamConfig.beam.agentsim.agents.vehicles.roadRestrictionWeightMultiplier.toFloat

    // Extract maxSpeed once, avoid Option operations in hot loop
    val maxSpeedOrNegative = vehicleType.restrictRoadsByFreeSpeedInMeterPerSecond.getOrElse(-1.0)
    val hasSpeedRestriction = maxSpeedOrNegative >= 0

    // Pre-compute constants
    val perMileCostFactor = perMileCost / METERS_IN_MILE
    val perMinuteCostFactor = perMinuteCost / 60.0

    // Return lambda - allocations in HERE are the problem!
    (edge: EdgeStore#Edge, legDurationSeconds: Int, traversalTimeSeconds: Float) => {
      val osmId = edge.getOSMID
      val isRestrictedPrecomputed = categoryRestrictions.get(osmId) == 1 // No boxing!

      val roadRestrictionWeightMultiplier: Float = {
        if (isRestrictedPrecomputed) {
          weightMultiplier
        } else if (hasSpeedRestriction) {
          val restriction = osmIdToRoadRestrictionTrove.get(osmId)
          if (restriction != null && restriction.isRestricted(vehicleType.vehicleCategory, maxSpeedOrNegative)) {
            weightMultiplier
          } else {
            1f
          }
        } else {
          1f
        }
      }

      val fare = traversalTimeSeconds * perMinuteCostFactor + edge.getLengthM * perMileCostFactor
      (traversalTimeSeconds + (timeValueOfMoney * (
        tollCalculator.calcTollByLinkId(edge.getEdgeIndex, startTime + legDurationSeconds) + fare
      )).toFloat) * roadRestrictionWeightMultiplier
    }
  }
}

object R5Wrapper {
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
