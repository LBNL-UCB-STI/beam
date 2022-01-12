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
import org.matsim.api.core.v01.{Coord, Id, Scenario}

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
  val skimmerEventFactory: AbstractSkimmerEventFactory
) {
  var requestsExecutionTime: RouteExecutionInfo = RouteExecutionInfo()

  private val dummyPersonAttributes = createDummyPersonAttribute

  private val modeChoiceCalculator: ModeChoiceCalculator = modeChoiceCalculatorFactory(dummyPersonAttributes)

  private val dummyCarVehicleType: BeamVehicleType = vehicleTypes.values
    .find(theType => theType.vehicleCategory == VehicleCategory.Car && theType.maxVelocity.isEmpty)
    .get

  private val dummyBodyVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Body).get

  private val dummyBikeVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Bike).get

  private val thresholdDistanceForBikeMeters: Double =
    beamConfig.beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.bike

  private val thresholdDistanceForWalkMeters: Double =
    beamConfig.beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.walk

  def route(srcIndex: GeoIndex, dstIndex: GeoIndex, requestTime: Int): ODRequester.Response = {
    val (srcCoord, dstCoord) = (srcIndex, dstIndex) match {
      case (h3SrcIndex: H3Index, h3DestIndex: H3Index) =>
        H3Clustering.getGeoIndexCenters(geoUtils, h3SrcIndex, h3DestIndex)
      case (tazSrcIndex: TAZIndex, tazDestIndex: TAZIndex) =>
        TAZClustering.getGeoIndexCenters(tazSrcIndex, tazDestIndex)
      case _ =>
        throw new MatchError(
          s"The type of src index (${srcIndex.getClass}) does not match the type of dst index (${dstIndex.getClass})."
        )
    }

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
        requestsExecutionTime = RouteExecutionInfo.sum(
          requestsExecutionTime,
          RouteExecutionInfo(r5ExecutionTime = System.nanoTime() - startExecution, r5Responses = 1)
        )
        response
      }
      else {
        Try(RoutingResponse.dummyRoutingResponse.get)
      }

    ODRequester.Response(srcIndex, dstIndex, considerModes, maybeResponse, requestTime)
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
    val streetVehicle: StreetVehicle = mode match {
      case BeamMode.CAR | BeamMode.DRIVE_TRANSIT =>
        StreetVehicle(
          Id.createVehicleId("dummy-car-for-skim-observations"),
          dummyCarVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          BeamMode.CAR,
          asDriver = true,
          needsToCalculateCost = false
        )
      case BeamMode.BIKE =>
        StreetVehicle(
          Id.createVehicleId("dummy-bike-for-skim-observations"),
          dummyBikeVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          BeamMode.BIKE,
          asDriver = true,
          needsToCalculateCost = false
        )
      case BeamMode.WALK | BeamMode.WALK_TRANSIT =>
        StreetVehicle(
          Id.createVehicleId("dummy-body-for-skim-observations"),
          dummyBodyVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        )
      case x =>
        throw new IllegalArgumentException(s"Get mode $x, but don't know what to do with it.")
    }
    streetVehicle
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
      valueOfTime = personVOTT,
      age = None,
      income = Some(dummyHouseholdAttributes.householdIncome)
    )
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
