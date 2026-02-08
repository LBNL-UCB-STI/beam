package beam.router.skim.urbansim

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone.{GeoIndex, H3Index, TAZIndex}
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, WALK, WALK_TRANSIT}
import beam.router.Router
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerEventFactory}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.util.Try

class ODRequester(
  val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  val router: Router,
  val scenario: Scenario,
  val geoUtils: GeoUtils,
  val beamModes: Seq[BeamMode],
  val beamConfig: BeamConfig,
  val modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory,
  val withTransit: Boolean,
  val buildDirectWalkRoute: Boolean,
  val buildDirectCarRoute: Boolean,
  val skimmerEventFactory: AbstractSkimmerEventFactory,
  val transportNetwork: Option[TransportNetwork] = None
) extends StrictLogging {

  // Thread-safe execution time tracking for concurrent batch processing
  private val _requestsExecutionTime: AtomicReference[RouteExecutionInfo] =
    new AtomicReference(RouteExecutionInfo())

  def requestsExecutionTime: RouteExecutionInfo = _requestsExecutionTime.get()

  private val dummyPersonAttributes = createDummyPersonAttribute

  private val modeChoiceCalculator: ModeChoiceCalculator = modeChoiceCalculatorFactory(dummyPersonAttributes)

  private val dummyCarVehicleType: BeamVehicleType = vehicleTypes.values
    .find(theType => theType.vehicleCategory == VehicleCategory.Car && theType.maxVelocity.isEmpty)
    .get

  private val dummyBodyVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Body).get

  private val dummyBikeVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Bike).get

  // Pre-created vehicle IDs to avoid per-request allocations
  private val dummyCarVehicleId = Id.createVehicleId("dummy-car-for-skim-observations")
  private val dummyBikeVehicleId = Id.createVehicleId("dummy-bike-for-skim-observations")
  private val dummyBodyVehicleId = Id.createVehicleId("dummy-body-for-skim-observations")

  // Pre-allocated mode array for drive-only routing
  private val driveOnlyModes: Array[BeamMode] = Array(BeamMode.CAR)

  private val thresholdDistanceForBikeMeters: Double =
    beamConfig.beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.bike

  private val thresholdDistanceForWalkMeters: Double =
    beamConfig.beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.walk

  // Standard link radius from config (typically 10km)
  private val linkRadiusMeters: Double = beamConfig.beam.routing.r5.linkRadiusMeters

  // Progressive fallback radii for remote areas (50km, 100km, 200km, 400km)
  private val fallbackRadiiMeters: Array[Double] = Array(50000.0, 100000.0, 200000.0, 400000.0)

  // Thread-safe cache for snapped coordinates to avoid repeated R5 lookups
  // Key: original coordinate string, Value: snapped coordinate (or original if unreachable)
  private val snappedCoordinateCache: ConcurrentHashMap[String, Coord] = new ConcurrentHashMap[String, Coord]()

  // Thread-safe set to track coordinates that couldn't be snapped even with fallback radius
  private val unreachableCoordinates: ConcurrentHashMap.KeySetView[String, java.lang.Boolean] =
    ConcurrentHashMap.newKeySet[String]()

  def route(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int): ODRequester.Response = {
    val (rawSrcCoord, rawDstCoord) = (srcIndex, dstIndex) match {
      case (h3SrcIndex: H3Index, h3DestIndex: H3Index) =>
        H3Clustering.getGeoIndexCenters(geoUtils, h3SrcIndex, h3DestIndex)
      case (tazSrcIndex: TAZIndex, tazDestIndex: TAZIndex) =>
        TAZClustering.getGeoIndexCenters(tazSrcIndex, tazDestIndex)
      case _ =>
        throw new MatchError(
          s"The type of src index (${srcIndex.getClass}) does not match the type of dst index (${dstIndex.getClass})."
        )
    }

    // Snap coordinates to nearest road to handle remote TAZ centroids
    val (srcCoord, dstCoord) = snapCoordinatesToRoad(rawSrcCoord, rawDstCoord)

    val dist = distanceWithMargin(srcCoord, dstCoord)
    val considerModes: Array[BeamMode] = beamModes.filter(mode => isDistanceWithinRange(mode, dist)).toArray
    val walkDistanceWithinRange = dist < thresholdDistanceForWalkMeters
    val streetVehicles = considerModes.map(createStreetVehicle(_, requestTime, srcCoord))
    val maybeResponse: Try[RoutingResponse] =
      if (streetVehicles.nonEmpty && (buildDirectCarRoute || buildDirectWalkRoute || withTransit)) Try {
        val routingReq = RoutingRequest(
          originUTM = srcCoord,
          destinationUTM = dstCoord,
          departureTime = requestTime,
          withTransit = withTransit,
          streetVehicles = streetVehicles,
          attributesOfIndividual = Some(dummyPersonAttributes),
          triggerId = -1
        )
        val startExecution = System.nanoTime()
        val response =
          router.calcRoute(
            routingReq,
            buildDirectCarRoute = buildDirectCarRoute,
            buildDirectWalkRoute = buildDirectWalkRoute && walkDistanceWithinRange
          )
        _requestsExecutionTime.updateAndGet(current =>
          RouteExecutionInfo.sum(
            current,
            RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
          )
        )
        response
      }
      else {
        Try(RoutingResponse.dummyRoutingResponse.get)
      }

    ODRequester.Response(srcIndex, dstIndex, considerModes, maybeResponse, requestTime)
  }

  /**
    * Route with explicit transit mode category and trip direction support.
    * Used for mode-filtered transit routing and return trip parking handling.
    *
    * @param workItem The work item containing OD pair, time, transit category, and trip direction
    * @return Response containing routing results
    */
  def route(workItem: ODWorkItem): ODRequester.Response = {
    val (srcIndex, dstIndex, requestTime) = (workItem.srcIndex, workItem.dstIndex, workItem.time)
    val (rawSrcCoord, rawDstCoord) = getCoordinates(srcIndex, dstIndex)

    // Snap coordinates to nearest road to handle remote TAZ centroids
    val (srcCoord, dstCoord) = snapCoordinatesToRoad(rawSrcCoord, rawDstCoord)

    val dist = distanceWithMargin(srcCoord, dstCoord)
    val considerModes: Array[BeamMode] = beamModes.filter(mode => isDistanceWithinRange(mode, dist)).toArray
    val walkDistanceWithinRange = dist < thresholdDistanceForWalkMeters

    // For return trips with DRIVE_TRANSIT, we need to handle vehicle location differently
    val (effectiveSrcCoord, vehicleLocationForReturn) = workItem.tripDirection match {
      case TripDirection.Return if considerModes.contains(DRIVE_TRANSIT) =>
        // For return trips, find the nearest transit stop to the destination (home)
        // where the car would have been parked during the outbound trip
        val nearestStop = findNearestTransitStopCoord(dstCoord)
        (srcCoord, nearestStop)
      case _ =>
        (srcCoord, None)
    }

    val streetVehicles = workItem.tripDirection match {
      case TripDirection.Return if vehicleLocationForReturn.isDefined =>
        // For return trips with drive-transit, create street vehicles with modified locations
        considerModes.flatMap { mode =>
          mode match {
            case DRIVE_TRANSIT =>
              // Vehicle is at the transit stop near destination, not at origin
              Some(createStreetVehicleAt(mode, requestTime, vehicleLocationForReturn.get))
            case _ =>
              Some(createStreetVehicle(mode, requestTime, effectiveSrcCoord))
          }
        }
      case _ =>
        considerModes.map(createStreetVehicle(_, requestTime, effectiveSrcCoord))
    }

    val transitModes = workItem.transitCategory.map(_.toR5TransitModes)

    val maybeResponse: Try[RoutingResponse] =
      if (streetVehicles.nonEmpty && (buildDirectCarRoute || buildDirectWalkRoute || withTransit)) Try {
        val routingReq = RoutingRequest(
          originUTM = effectiveSrcCoord,
          destinationUTM = dstCoord,
          departureTime = requestTime,
          withTransit = withTransit,
          streetVehicles = streetVehicles,
          attributesOfIndividual = Some(dummyPersonAttributes),
          triggerId = -1,
          transitModes = transitModes
        )
        val startExecution = System.nanoTime()
        val response =
          router.calcRoute(
            routingReq,
            buildDirectCarRoute = buildDirectCarRoute,
            buildDirectWalkRoute = buildDirectWalkRoute && walkDistanceWithinRange
          )
        _requestsExecutionTime.updateAndGet(current =>
          RouteExecutionInfo.sum(
            current,
            RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
          )
        )
        response
      }
      else {
        Try(RoutingResponse.dummyRoutingResponse.get)
      }

    ODRequester.Response(srcIndex, dstIndex, considerModes, maybeResponse, requestTime)
  }

  /**
    * Optimized route method for drive-only skim generation.
    * Reduces object allocations by reusing pre-created vehicle IDs and avoiding
    * unnecessary distance checks and mode filtering.
    */
  def routeDriveOnly(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int): ODRequester.Response = {
    val (rawSrcCoord, rawDstCoord) = (srcIndex, dstIndex) match {
      case (tazSrc: TAZIndex, tazDst: TAZIndex) =>
        TAZClustering.getGeoIndexCenters(tazSrc, tazDst)
      case (h3Src: H3Index, h3Dst: H3Index) =>
        H3Clustering.getGeoIndexCenters(geoUtils, h3Src, h3Dst)
      case _ =>
        throw new IllegalArgumentException(
          s"Expected matching index types, got ${srcIndex.getClass} and ${dstIndex.getClass}"
        )
    }

    // Snap coordinates to nearest road to handle remote TAZ centroids
    val (srcCoord, dstCoord) = snapCoordinatesToRoad(rawSrcCoord, rawDstCoord)

    val streetVehicle = StreetVehicle(
      dummyCarVehicleId,
      dummyCarVehicleType.id,
      new SpaceTime(srcCoord, requestTime),
      BeamMode.CAR,
      asDriver = true,
      needsToCalculateCost = false
    )

    val routingReq = RoutingRequest(
      originUTM = srcCoord,
      destinationUTM = dstCoord,
      departureTime = requestTime,
      withTransit = false,
      streetVehicles = Array(streetVehicle),
      attributesOfIndividual = Some(dummyPersonAttributes),
      triggerId = -1
    )

    val maybeResponse = Try {
      val startExecution = System.nanoTime()
      val response = router.calcRoute(routingReq, buildDirectCarRoute = true, buildDirectWalkRoute = false)
      _requestsExecutionTime.updateAndGet(current =>
        RouteExecutionInfo.sum(
          current,
          RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
        )
      )
      response
    }

    ODRequester.Response(srcIndex, dstIndex, driveOnlyModes, maybeResponse, requestTime)
  }

  def createSkimEvent(
    origin: GeoIndex,
    destination: GeoIndex,
    beamMode: BeamMode,
    trip: EmbodiedBeamTrip,
    requestTime: Int
  ): AbstractSkimmerEvent = {
    // In case of CAR AND BIKE we have to create two dummy legs: walk to the CAR in the beginning and walk when CAR has arrived
    val theTrip = if (beamMode == BeamMode.CAR || beamMode == BeamMode.BIKE) {
      val actualLegs = trip.legs
      EmbodiedBeamTrip(
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.head.beamLeg.startTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = false,
          location = actualLegs.head.beamLeg.travelPath.startPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        ) +:
        actualLegs :+
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.last.beamLeg.endTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = true,
          location = actualLegs.last.beamLeg.travelPath.endPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        ),
        trip.router
      )
    } else {
      trip
    }

    val generalizedTime =
      modeChoiceCalculator.getGeneralizedTimeOfTrip(theTrip, Some(dummyPersonAttributes), None)
    val generalizedCost = modeChoiceCalculator.getNonTimeCost(theTrip) + dummyPersonAttributes.getVOT(generalizedTime)
    val energyConsumption = dummyCarVehicleType.primaryFuelConsumptionInJoulePerMeter * theTrip.legs
      .map(_.beamLeg.travelPath.distanceInM)
      .sum

    skimmerEventFactory.createEvent(
      origin = origin.value,
      destination = destination.value,
      eventTime = requestTime,
      trip = theTrip,
      generalizedTimeInHours = generalizedTime,
      generalizedCost = generalizedCost,
      energyConsumption = energyConsumption
    )
  }

  private def distanceWithMargin(srcCoord: Coord, dstCoord: Coord): Double = {
    GeoUtils.distFormula(srcCoord, dstCoord) * 1.4
  }

  def isDistanceWithinRange(mode: BeamMode, dist: Double): Boolean = {
    mode match {
      case BeamMode.CAR           => true
      case BeamMode.DRIVE_TRANSIT => true
      case BeamMode.WALK_TRANSIT  => true
      case BeamMode.WALK          => true
      case BeamMode.BIKE          => dist < thresholdDistanceForBikeMeters
      case x                      => throw new IllegalStateException(s"Don't know what to do with $x")
    }
  }

  def createStreetVehicle(mode: BeamMode, requestTime: Int, srcCoord: Coord): StreetVehicle = {
    val (vehicleId, vehicleTypeId, beamMode) = mode match {
      case BeamMode.CAR | BeamMode.DRIVE_TRANSIT =>
        (dummyCarVehicleId, dummyCarVehicleType.id, BeamMode.CAR)
      case BeamMode.BIKE =>
        (dummyBikeVehicleId, dummyBikeVehicleType.id, BeamMode.BIKE)
      case BeamMode.WALK | BeamMode.WALK_TRANSIT =>
        (dummyBodyVehicleId, dummyBodyVehicleType.id, WALK)
      case x =>
        throw new IllegalArgumentException(s"Get mode $x, but don't know what to do with it.")
    }
    StreetVehicle(
      vehicleId,
      vehicleTypeId,
      new SpaceTime(srcCoord, requestTime),
      beamMode,
      asDriver = true,
      needsToCalculateCost = false
    )
  }

  private def createDummyPersonAttribute: AttributesOfIndividual = {
    val medianHouseholdByIncome = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .toList
      .sortBy(_.getIncome.getIncome)
      .drop(scenario.getHouseholds.getHouseholds.size() / 2)
      .head
    val dummyHouseholdAttributes = new HouseholdAttributes(
      householdId = medianHouseholdByIncome.getId.toString,
      householdIncome = medianHouseholdByIncome.getIncome.getIncome,
      householdSize = 1,
      numCars = 1,
      numBikes = 1
    )
    val personVOTT = PopulationAdjustment
      .incomeToValueOfTime(dummyHouseholdAttributes.householdIncome)
      .getOrElse(beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime)
    AttributesOfIndividual(
      householdAttributes = dummyHouseholdAttributes,
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(CAR, WALK_TRANSIT, BIKE, DRIVE_TRANSIT),
      rideHailServiceSubscription = Seq.empty,
      valueOfTime = personVOTT,
      age = None,
      income = Some(dummyHouseholdAttributes.householdIncome)
    )
  }

  /**
    * Get coordinates from GeoIndex pair.
    */
  private def getCoordinates(srcIndex: GeoIndex, dstIndex: GeoIndex): (Coord, Coord) = {
    (srcIndex, dstIndex) match {
      case (h3SrcIndex: H3Index, h3DestIndex: H3Index) =>
        H3Clustering.getGeoIndexCenters(geoUtils, h3SrcIndex, h3DestIndex)
      case (tazSrcIndex: TAZIndex, tazDestIndex: TAZIndex) =>
        TAZClustering.getGeoIndexCenters(tazSrcIndex, tazDestIndex)
      case _ =>
        throw new MatchError(
          s"The type of src index (${srcIndex.getClass}) does not match the type of dst index (${dstIndex.getClass})."
        )
    }
  }

  /**
    * Create a street vehicle at a specific location (used for return trips where vehicle is at transit stop).
    */
  private def createStreetVehicleAt(mode: BeamMode, requestTime: Int, vehicleCoord: Coord): StreetVehicle = {
    val (vehicleId, vehicleTypeId, beamMode) = mode match {
      case BeamMode.CAR | BeamMode.DRIVE_TRANSIT =>
        (dummyCarVehicleId, dummyCarVehicleType.id, BeamMode.CAR)
      case BeamMode.BIKE =>
        (dummyBikeVehicleId, dummyBikeVehicleType.id, BeamMode.BIKE)
      case BeamMode.WALK | BeamMode.WALK_TRANSIT =>
        (dummyBodyVehicleId, dummyBodyVehicleType.id, WALK)
      case x =>
        throw new IllegalArgumentException(s"Get mode $x, but don't know what to do with it.")
    }
    StreetVehicle(
      vehicleId,
      vehicleTypeId,
      new SpaceTime(vehicleCoord, requestTime),
      beamMode,
      asDriver = true,
      needsToCalculateCost = false
    )
  }

  /**
    * Find the nearest transit stop to a given coordinate.
    * Uses R5's transit layer to locate stops that could be used for park-and-ride.
    *
    * @param coord The coordinate to search near (in UTM)
    * @return The coordinate of the nearest transit stop (in UTM), or None if no transit network available
    */
  private def findNearestTransitStopCoord(coord: Coord): Option[Coord] = {
    transportNetwork.flatMap { network =>
      val transitLayer = network.transitLayer
      val streetLayer = network.streetLayer
      if (transitLayer == null || transitLayer.stopIdForIndex == null || transitLayer.stopIdForIndex.size() == 0) {
        None
      } else {
        // Convert to WGS84 for comparison with transit stop coordinates
        val wgsCoord = geoUtils.utm2Wgs(coord)

        var minDistance = Double.MaxValue
        var nearestStopCoord: Option[Coord] = None

        // Search through transit stops to find the nearest one
        val stopCount = transitLayer.stopIdForIndex.size()
        var i = 0
        while (i < stopCount) {
          // Get the street vertex for this stop
          val streetVertexIdx = transitLayer.streetVertexForStop.get(i)
          if (streetVertexIdx >= 0) {
            // Get the coordinates from the street layer
            val vertex = streetLayer.vertexStore.getCursor(streetVertexIdx)
            val stopLat = vertex.getLat
            val stopLon = vertex.getLon

            val stopCoord = new Coord(stopLon, stopLat)
            val distance = GeoUtils.distFormula(wgsCoord, stopCoord)
            if (distance < minDistance) {
              minDistance = distance
              nearestStopCoord = Some(geoUtils.wgs2Utm(stopCoord))
            }
          }
          i += 1
        }

        nearestStopCoord
      }
    }
  }

  /**
    * Snap a coordinate to the nearest road network vertex.
    * First tries with the standard linkRadiusMeters, then progressively tries larger radii
    * (50km, 100km, 200km, 400km) until a road is found.
    * Results are cached to avoid repeated R5 lookups.
    *
    * @param coord The coordinate to snap (in UTM)
    * @return The snapped coordinate (in UTM), or the original if snapping fails
    */
  private def snapToNearestRoad(coord: Coord): Coord = {
    val coordKey = s"${coord.getX},${coord.getY}"

    // Check cache first (thread-safe)
    val cached = snappedCoordinateCache.get(coordKey)
    if (cached != null) {
      return cached
    }

    val snapped = transportNetwork match {
      case Some(network) =>
        val streetLayer = network.streetLayer
        val wgsCoord = geoUtils.utm2Wgs(coord)

        // Try with standard radius first
        var split = streetLayer.findSplit(
          wgsCoord.getY,
          wgsCoord.getX,
          linkRadiusMeters,
          com.conveyal.r5.profile.StreetMode.CAR
        )

        // If standard radius fails, try progressively larger fallback radii (50km, 100km, 200km, 400km)
        if (split == null) {
          var i = 0
          while (split == null && i < fallbackRadiiMeters.length) {
            val fallbackRadius = fallbackRadiiMeters(i)
            split = streetLayer.findSplit(
              wgsCoord.getY,
              wgsCoord.getX,
              fallbackRadius,
              com.conveyal.r5.profile.StreetMode.CAR
            )
            if (split != null) {
              val vertex = streetLayer.vertexStore.getCursor(split.vertex0)
              logger.warn(
                s"[SNAP-SUCCESS] Coordinate (${wgsCoord.getY}, ${wgsCoord.getX}) snapped to (${vertex.getLat}, ${vertex.getLon}) using ${fallbackRadius.toInt}m radius"
              )
            }
            i += 1
          }
        }

        if (split != null) {
          // Get the snapped coordinate from the split
          val vertex = streetLayer.vertexStore.getCursor(split.vertex0)
          val snappedWgs = new Coord(vertex.getLon, vertex.getLat)
          geoUtils.wgs2Utm(snappedWgs)
        } else {
          // Coordinate might be outside network bounds - try snapping from boundary edge
          val envelope = streetLayer.getEnvelope
          val clampedLon = Math.max(envelope.getMinX, Math.min(envelope.getMaxX, wgsCoord.getX))
          val clampedLat = Math.max(envelope.getMinY, Math.min(envelope.getMaxY, wgsCoord.getY))

          // Only try boundary snapping if coordinate was actually outside bounds
          if (clampedLon != wgsCoord.getX || clampedLat != wgsCoord.getY) {
            // Try snapping from the clamped boundary point
            split = streetLayer.findSplit(
              clampedLat,
              clampedLon,
              fallbackRadiiMeters.last, // Use max radius from boundary
              com.conveyal.r5.profile.StreetMode.CAR
            )

            if (split != null) {
              val vertex = streetLayer.vertexStore.getCursor(split.vertex0)
              logger.warn(
                s"[SNAP-BOUNDARY] Coordinate (${wgsCoord.getY}, ${wgsCoord.getX}) outside network bounds, " +
                s"snapped via boundary to (${vertex.getLat}, ${vertex.getLon})"
              )
              val snappedWgs = new Coord(vertex.getLon, vertex.getLat)
              geoUtils.wgs2Utm(snappedWgs)
            } else {
              // Even boundary snapping failed
              if (unreachableCoordinates.add(coordKey)) {
                logger.warn(
                  s"[SNAP-FAILED] Could not snap (${wgsCoord.getY}, ${wgsCoord.getX}) even from boundary ($clampedLat, $clampedLon)"
                )
              }
              coord
            }
          } else {
            // Coordinate is within bounds but still couldn't snap
            if (unreachableCoordinates.add(coordKey)) {
              logger.warn(
                s"[SNAP-FAILED] Could not snap (${wgsCoord.getY}, ${wgsCoord.getX}) to road even with ${fallbackRadiiMeters.last.toInt}m radius"
              )
            }
            coord
          }
        }

      case None =>
        // Log once when transport network is not available
        if (unreachableCoordinates.add("NO_NETWORK")) {
          logger.warn("[SNAP-SKIP] No transport network available for coordinate snapping")
        }
        coord
    }

    // Cache the result (putIfAbsent is thread-safe, returns existing value if already present)
    val existing = snappedCoordinateCache.putIfAbsent(coordKey, snapped)
    if (existing != null) existing else snapped
  }

  /**
    * Snap both source and destination coordinates to the road network.
    * This ensures routing requests use coordinates that R5 can actually reach.
    *
    * @param srcCoord Original source coordinate (in UTM)
    * @param dstCoord Original destination coordinate (in UTM)
    * @return Tuple of (snapped source, snapped destination) coordinates
    */
  private def snapCoordinatesToRoad(srcCoord: Coord, dstCoord: Coord): (Coord, Coord) = {
    (snapToNearestRoad(srcCoord), snapToNearestRoad(dstCoord))
  }
}

object ODRequester {

  case class Response(
    srcIndex: GeoIndex,
    dstIndex: GeoIndex,
    considerModes: Array[BeamMode],
    maybeRoutingResponse: Try[RoutingResponse],
    requestTime: Int
  )
}
