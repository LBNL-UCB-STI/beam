package beam.router.skim.urbansim

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone.H3Index
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.R5Wrapper
import beam.router.skim.ODSkimmerEvent
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import org.matsim.api.core.v01.{Coord, Id, Scenario}

import scala.collection.JavaConverters._
import scala.util.Try

class ODR5Requester(
  val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  val r5Wrapper: R5Wrapper,
  val scenario: Scenario,
  val geoUtils: GeoUtils,
  val beamModes: Array[BeamMode],
  val beamConfig: BeamConfig,
  val modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory,
  val withTransit: Boolean
) {
  val requestTime: Int = (beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHour * 3600).toInt

  private val dummyPersonAttributes = createDummyPersonAttribute

  private val modeChoiceCalculator: ModeChoiceCalculator = modeChoiceCalculatorFactory(dummyPersonAttributes)

  private val dummyCarVehicleType: BeamVehicleType = vehicleTypes.values
    .find(theType => theType.vehicleCategory == VehicleCategory.Car && theType.maxVelocity.isEmpty)
    .get
  private val dummyBodyVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Body).get

  private val dummyBikeVehicleType: BeamVehicleType =
    vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Bike).get

  private val thresholdDistanceForBikeMeteres: Double = 20 * 1.60934 * 1E3 // 20 miles to meters

  def route(srcIndex: H3Index, dstIndex: H3Index): ODR5Requester.Response = {
    val (srcCoord, dstCoord) = H3Clustering.getGeoIndexCenters(geoUtils, srcIndex, dstIndex)
    val dist = distanceWithMargin(srcCoord, dstCoord)
    val considerModes: Array[BeamMode] = beamModes.filter(mode => isDistanceWithinRange(mode, dist))
    val maybeResponse = Try {
      val streetVehicles = considerModes.map(createStreetVehicle(_, requestTime, srcCoord))
      val routingReq = RoutingRequest(
        originUTM = srcCoord,
        destinationUTM = dstCoord,
        departureTime = requestTime,
        withTransit = withTransit,
        streetVehicles = streetVehicles,
        attributesOfIndividual = Some(dummyPersonAttributes)
      )
      r5Wrapper.calcRoute(routingReq)
    }
    ODR5Requester.Response(srcIndex, dstIndex, considerModes, maybeResponse)
  }

  def createSkimEvent(
    origin: H3Index,
    destination: H3Index,
    beamMode: BeamMode,
    trip: EmbodiedBeamTrip
  ): ODSkimmerEvent = {
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
        )
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
    ODSkimmerEvent(
      origin = origin.value,
      destination = destination.value,
      eventTime = requestTime,
      trip = theTrip,
      generalizedTimeInHours = generalizedTime,
      generalizedCost = generalizedCost,
      energyConsumption = energyConsumption,
      // If you change this name, make sure it is properly reflected in `AbstractSkimmer.handleEvent`
      skimName = "od-skimmer"
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
      case BeamMode.BIKE =>
        dist < thresholdDistanceForBikeMeteres
      case x => throw new IllegalStateException(s"Don't know what to do with $x")
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
          asDriver = true
        )
      case BeamMode.BIKE =>
        StreetVehicle(
          Id.createVehicleId("dummy-bike-for-skim-observations"),
          dummyBikeVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          BeamMode.BIKE,
          asDriver = true
        )
      case BeamMode.WALK | BeamMode.WALK_TRANSIT =>
        StreetVehicle(
          Id.createVehicleId("dummy-body-for-skim-observations"),
          dummyBodyVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          WALK,
          asDriver = true
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

object ODR5Requester {
  case class Response(
    srcIndex: H3Index,
    dstIndex: H3Index,
    considerModes: Array[BeamMode],
    maybeRoutingResponse: Try[RoutingResponse]
  )
}
