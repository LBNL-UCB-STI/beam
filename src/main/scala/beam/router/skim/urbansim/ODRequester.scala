package beam.router.skim.urbansim

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone.{GeoIndex, H3Index, TAZIndex}
import beam.router.BeamRouter.{IntermodalUse, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, WALK, WALK_TRANSIT}
import beam.router.Router
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerEventFactory}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import beam.router.osm.TollCalculator
import beam.router.skim.{ActivitySimPathType, ActivitySimSkimmerEventFactory}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.util.{Success, Try}

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
  val transportNetwork: Option[TransportNetwork] = None,
  tollCalculator: TollCalculator = null,
  val bifurcateTolls: Boolean = false
) extends StrictLogging {

  val actualTollCalculator: TollCalculator =
    if (tollCalculator != null) tollCalculator else new TollCalculator(beamConfig)

  // Thread-safe execution time tracking for concurrent batch processing
  private val _requestsExecutionTime: AtomicReference[RouteExecutionInfo] =
    new AtomicReference(RouteExecutionInfo())

  def requestsExecutionTime: RouteExecutionInfo = _requestsExecutionTime.get()

  private val dummyPersonAttributes = createDummyPersonAttribute
  private val dummyPersonAttributesFastest = dummyPersonAttributes.copy(valueOfTime = 10000000.0)
  private val dummyPersonAttributesTollAvoid = dummyPersonAttributes.copy(valueOfTime = 0.0001)

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
  // Pre-allocated mode array for walk-only routing
  private val walkOnlyModes: Array[BeamMode] = Array(BeamMode.WALK)

  // Check if this requester is configured for drive-only mode (optimization flag)
  val isDriveOnly: Boolean = beamModes.size == 1 && beamModes.head == BeamMode.CAR && !withTransit
  // Check if this requester is configured for walk-only mode (optimization flag)
  val isWalkOnly: Boolean = beamModes.size == 1 && beamModes.head == BeamMode.WALK && !withTransit

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

  // Thread-safe set to track coordinates that couldn't be snapped (logged once per unique coordinate)
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
          attributesOfIndividual = Some(dummyPersonAttributesFastest),
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

    if (workItem.tripDirection == TripDirection.Return) {
      if (workItem.parkingLocations.isEmpty) {
        // No parking locations from outbound stage; no return drive-transit possible
        ODRequester.Response(
          srcIndex,
          dstIndex,
          considerModes,
          Success(RoutingResponse.dummyRoutingResponse.get),
          requestTime
        )
      } else {
        // Return trip: traveler is at srcCoord (e.g. workplace) returning to dstCoord (e.g. home).
        // Candidate vehicles parked at transit stations from outbound trips:
        val parkedVehicles = workItem.parkingLocations.toVector.zipWithIndex.map { case (stationCoord, idx) =>
          StreetVehicle(
            id = Id.createVehicleId(s"return-car-${srcIndex.value}-${dstIndex.value}-$idx"),
            vehicleTypeId = dummyCarVehicleType.id,
            locationUTM = new SpaceTime(stationCoord, requestTime),
            mode = BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = false
          )
        }
        val walkVehicle = createStreetVehicle(BeamMode.WALK, requestTime, srcCoord)

        val routingReq = RoutingRequest(
          originUTM = srcCoord,
          destinationUTM = dstCoord,
          departureTime = requestTime,
          withTransit = true,
          streetVehicles = parkedVehicles :+ walkVehicle,
          streetVehiclesUseIntermodalUse = IntermodalUse.Egress,
          requestedMode = Some(BeamMode.DRIVE_TRANSIT),
          attributesOfIndividual = Some(dummyPersonAttributes),
          triggerId = -1,
          transitModes = workItem.transitCategory.map(_.toR5TransitModes)
        )

        val startExecution = System.nanoTime()
        val response = router.calcRoute(
          routingReq,
          buildDirectCarRoute = false,
          buildDirectWalkRoute = false
        )

        _requestsExecutionTime.updateAndGet(current =>
          RouteExecutionInfo.sum(
            current,
            RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
          )
        )
        ODRequester.Response(srcIndex, dstIndex, considerModes, Success(response), requestTime)
      }
    } else if (workItem.avoidTolls) {
      routeDriveOnly(workItem.srcIndex, workItem.dstIndex, workItem.time, avoidTolls = true)
    } else {
      val streetVehicles = considerModes.map(createStreetVehicle(_, requestTime, srcCoord))
      val transitModes = workItem.transitCategory.map(_.toR5TransitModes)

      val maybeResponse: Try[RoutingResponse] =
        if (streetVehicles.nonEmpty && (buildDirectCarRoute || buildDirectWalkRoute || withTransit)) Try {
          val routingReq = RoutingRequest(
            originUTM = srcCoord,
            destinationUTM = dstCoord,
            departureTime = requestTime,
            withTransit = withTransit,
            streetVehicles = streetVehicles,
            streetVehiclesUseIntermodalUse = IntermodalUse.Access,
            attributesOfIndividual = Some(if (bifurcateTolls) dummyPersonAttributesFastest else dummyPersonAttributes),
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
        } else {
          Try(RoutingResponse.dummyRoutingResponse.get)
        }

      ODRequester.Response(srcIndex, dstIndex, considerModes, maybeResponse, requestTime, avoidTolls = false)
    }
  }

  /**
    * Optimized route method for drive-only skim generation.
    * Reduces object allocations by reusing pre-created vehicle IDs and avoiding
    * unnecessary distance checks and mode filtering.
    */
  def routeDriveOnly(
    srcIndex: GeoIndex,
    dstIndex: GeoIndex,
    requestTime: Int,
    avoidTolls: Boolean = false
  ): ODRequester.Response = {
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

    val personAttributes =
      if (avoidTolls) dummyPersonAttributesTollAvoid
      else if (bifurcateTolls) dummyPersonAttributesFastest
      else dummyPersonAttributes

    val routingReq = RoutingRequest(
      originUTM = srcCoord,
      destinationUTM = dstCoord,
      departureTime = requestTime,
      withTransit = false,
      streetVehicles = Array(streetVehicle),
      attributesOfIndividual = Some(personAttributes),
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

    ODRequester.Response(srcIndex, dstIndex, driveOnlyModes, maybeResponse, requestTime, avoidTolls = avoidTolls)
  }

  /**
    * Optimized route method for walk-only skim generation.
    * Reuses pre-created dummy body vehicle IDs and avoids unnecessary transit routing.
    * Short-circuits with an empty response if the OD distance exceeds thresholdDistanceForWalkMeters.
    */
  def routeWalkOnly(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int): ODRequester.Response = {
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

    val dist = distanceWithMargin(rawSrcCoord, rawDstCoord)
    if (dist >= thresholdDistanceForWalkMeters) {
      ODRequester.Response(srcIndex, dstIndex, walkOnlyModes, Try(RoutingResponse.dummyRoutingResponse.get), requestTime)
    } else {
      val (srcCoord, dstCoord) = snapCoordinatesToRoad(rawSrcCoord, rawDstCoord)
      val streetVehicle = StreetVehicle(
        dummyBodyVehicleId,
        dummyBodyVehicleType.id,
        new SpaceTime(srcCoord, requestTime),
        BeamMode.WALK,
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
        val response = router.calcRoute(routingReq, buildDirectCarRoute = false, buildDirectWalkRoute = true)
        _requestsExecutionTime.updateAndGet(current =>
          RouteExecutionInfo.sum(
            current,
            RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
          )
        )
        response
      }

      ODRequester.Response(srcIndex, dstIndex, walkOnlyModes, maybeResponse, requestTime)
    }
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

    skimmerEventFactory match {
      case asimFactory: ActivitySimSkimmerEventFactory =>
        val tollInDollars = calculateToll(theTrip)
        asimFactory.createEvent(
          origin = origin.value,
          destination = destination.value,
          eventTime = requestTime,
          trip = theTrip,
          generalizedTimeInHours = generalizedTime,
          generalizedCost = generalizedCost,
          energyConsumption = energyConsumption,
          pathTypeOverride = None,
          costOverrideInDollars = None,
          bridgeTollInCents = tollInDollars * 100.0,
          valueTollInCents = 0.0
        )
      case _ =>
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
  }

  def calculateToll(trip: EmbodiedBeamTrip): Double = {
    trip.beamLegs.collect {
      case leg if leg.mode == BeamMode.CAR || leg.mode == BeamMode.CAV =>
        val linkToll = actualTollCalculator.calcTollByLinkIds(leg.travelPath)
        val osmToll = transportNetwork match {
          case Some(tn) if actualTollCalculator.hasAnyWayTolls =>
            try {
              val osmIds = leg.travelPath.linkIds.flatMap { edgeId =>
                if (edgeId >= 0 && edgeId < tn.streetLayer.edgeStore.nEdges) {
                  Some(tn.streetLayer.edgeStore.getCursor(edgeId).getOSMID)
                } else None
              }.toIndexedSeq
              actualTollCalculator.calcTollByOsmIds(osmIds)
            } catch {
              case NonFatal(_) => 0.0
            }
          case _ => 0.0
        }
        linkToll + osmToll
    }.sum
  }

  def createActivitySimSkimEvent(
    origin: GeoIndex,
    destination: GeoIndex,
    pathType: ActivitySimPathType,
    trip: EmbodiedBeamTrip,
    requestTime: Int,
    tollCostInDollars: Double = 0.0,
    bridgeTollInCents: Double = 0.0,
    valueTollInCents: Double = 0.0
  ): AbstractSkimmerEvent = {
    val theTrip = if (ActivitySimPathType.isCar(pathType) && trip.legs.forall(_.beamLeg.mode != WALK)) {
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

    skimmerEventFactory match {
      case asimFactory: ActivitySimSkimmerEventFactory =>
        asimFactory.createEvent(
          origin = origin.value,
          destination = destination.value,
          eventTime = requestTime,
          trip = theTrip,
          generalizedTimeInHours = generalizedTime,
          generalizedCost = generalizedCost,
          energyConsumption = energyConsumption,
          pathTypeOverride = Some(pathType),
          costOverrideInDollars = Some(tollCostInDollars),
          bridgeTollInCents = bridgeTollInCents,
          valueTollInCents = valueTollInCents
        )
      case _ =>
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
            i += 1
          }
        }

        if (split != null) {
          // Get the snapped coordinate from the split
          val vertex = streetLayer.vertexStore.getCursor(split.vertex0)
          val snappedWgs = new Coord(vertex.getLon, vertex.getLat)
          geoUtils.wgs2Utm(snappedWgs)
        } else {
          // findSplit failed - coordinate is likely in an area with no roads (e.g., mountains)
          // Brute-force search: find the closest vertex in the entire network
          val closestVertex = findClosestNetworkVertex(streetLayer, wgsCoord)

          closestVertex match {
            case Some((vertexLat, vertexLon, _)) =>
              val snappedWgs = new Coord(vertexLon, vertexLat)
              geoUtils.wgs2Utm(snappedWgs)
            case None =>
              if (unreachableCoordinates.add(coordKey)) {
                logger.warn(s"Could not snap coordinate (${wgsCoord.getY}, ${wgsCoord.getX}) - no vertices in network")
              }
              coord
          }
        }

      case None =>
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

  /**
    * Find the closest vertex in the street network using brute-force search.
    * Used as a last resort when findSplit fails (e.g., for coordinates in roadless areas).
    *
    * @param streetLayer The R5 street layer
    * @param wgsCoord The target coordinate in WGS84
    * @return Option of (lat, lon, distanceKm) for the closest vertex, or None if network is empty
    */
  private def findClosestNetworkVertex(
    streetLayer: com.conveyal.r5.streets.StreetLayer,
    wgsCoord: Coord
  ): Option[(Double, Double, Double)] = {
    val vertexStore = streetLayer.vertexStore
    val numVertices = vertexStore.getVertexCount

    if (numVertices == 0) return None

    var minDistSq = Double.MaxValue
    var closestLat = 0.0
    var closestLon = 0.0

    val cursor = vertexStore.getCursor()
    var i = 0
    while (i < numVertices) {
      cursor.seek(i)
      val vLat = cursor.getLat
      val vLon = cursor.getLon

      val dLat = vLat - wgsCoord.getY
      val dLon = vLon - wgsCoord.getX
      val distSq = dLat * dLat + dLon * dLon

      if (distSq < minDistSq) {
        minDistSq = distSq
        closestLat = vLat
        closestLon = vLon
      }
      i += 1
    }

    val distKm = Math.sqrt(minDistSq) * 111.0
    Some((closestLat, closestLon, distKm))
  }
}

object ODRequester {

  case class Response(
    srcIndex: GeoIndex,
    dstIndex: GeoIndex,
    considerModes: Array[BeamMode],
    maybeRoutingResponse: Try[RoutingResponse],
    requestTime: Int,
    avoidTolls: Boolean = false
  )
}
