package beam.router

import beam.agentsim.agents.choice.mode.PtFares
import beam.agentsim.agents.ridehail.RideHailMatching.CustomerRequest
import beam.agentsim.agents.vehicles.{BeamVehicleType, FuelType, VehicleCategory, VehicleEnergy}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.{Location, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ODSkimmer.Skim
import beam.router.skim.ODSkims
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam
import beam.sim.config.BeamConfig.Beam.Routing
import beam.utils.DateUtils
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.{any, _}

import java.time.ZonedDateTime
import scala.collection.concurrent.TrieMap

/**
  * Specs for BeamRouter
  */
class BeamRouterSpec extends FlatSpec with MockitoSugar {
  it should "use odSkim travel times for car" in {
    val beamScenario = getBeamScenario(1.0)

    val updatedDuration = 1444
    val skim = Skim(
      time = updatedDuration,
      generalizedTime = 1.0,
      generalizedCost = 1.0,
      distance = 1.0,
      cost = 1.0,
      count = 1,
      energy = 1.0
    )
    val odSkims: ODSkims = getSkimMock(skim)
    val updatedRoutingResponse =
      BeamRouter.replaceTravelTimeForCarModeWithODSkims(
        carRoutingResponse,
        odSkims,
        beamScenario,
        GeoUtils.GeoUtilsNad83
      )
    assert(
      updatedRoutingResponse.itineraries.head.beamLegs.head.duration == updatedDuration,
      "replacing car travel time did not work"
    )
  }

  it should "not use odSkim travel times for walk" in {
    val updatedDuration = 1444
    val skim = Skim(
      time = updatedDuration,
      generalizedTime = 1.0,
      generalizedCost = 1.0,
      distance = 1.0,
      cost = 1.0,
      count = 1,
      energy = 1.0
    )
    val odSkims: ODSkims = getSkimMock(skim)
    val updatedRoutingResponse =
      BeamRouter.replaceTravelTimeForCarModeWithODSkims(
        walkRoutingResponse,
        odSkims,
        mock[BeamScenario],
        mock[GeoUtils]
      )
    assert(
      updatedRoutingResponse.itineraries.head.beamLegs.head.duration != updatedDuration,
      "walk travel times should not be replaced"
    )
  }

  def getBeamScenario(skimTravelTimesScalingFactor: Double): BeamScenario = {
    val beamConfig = BeamConfig(
      ConfigFactory
        .parseString(s"""
           |beam.routing.skimTravelTimesScalingFactor =  $skimTravelTimesScalingFactor
        """.stripMargin)
        .withFallback(testConfig("test/input/sf-light/sf-light-0.5k.conf"))
        .resolve()
    )

    val vehicleType = BeamVehicleType(
      id = Id.create("car", classOf[BeamVehicleType]),
      seatingCapacity = 1,
      standingRoomCapacity = 1,
      lengthInMeter = 3,
      primaryFuelType = FuelType.Gasoline,
      primaryFuelConsumptionInJoulePerMeter = 0.1,
      primaryFuelCapacityInJoule = 0.1,
      vehicleCategory = VehicleCategory.Car
    )
    val vehicleTypes = Map(vehicleType.id                -> vehicleType)
    val fuelTypePrices = Map(vehicleType.primaryFuelType -> 10.0)
    val tazMap = mock[TAZTreeMap]
    when(tazMap.getTAZ(any[java.lang.Double](), any[java.lang.Double]()))
      .thenReturn(TAZ.DefaultTAZ)

    BeamScenario(
      fuelTypePrices = fuelTypePrices,
      vehicleTypes = vehicleTypes,
      TrieMap.empty,
      vehicleEnergy = mock[VehicleEnergy],
      beamConfig = beamConfig,
      dates = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      ),
      ptFares = PtFares(List.empty),
      transportNetwork = mock[TransportNetwork],
      network = mock[Network],
      tazTreeMap = tazMap,
      linkQuadTree = new QuadTree[Link](0, 0, 10, 10),
      linkIdMapping = Map.empty,
      linkToTAZMapping = Map.empty,
      modeIncentives = null,
      h3taz = null
    )
  }

  def createRoutingResponse(beamMode: BeamMode): RoutingResponse = {
    RoutingResponse(
      itineraries = Vector(
        EmbodiedBeamTrip(
          legs = Vector(
            EmbodiedBeamLeg(
              beamLeg = BeamLeg(
                startTime = 28800,
                mode = beamMode,
                duration = 100,
                travelPath = BeamPath(
                  linkIds = Vector(1, 2),
                  linkTravelTime = Vector(50, 50),
                  transitStops = None,
                  startPoint = SpaceTime(0.0, 0.0, 28800),
                  endPoint = SpaceTime(1.0, 1.0, 28850),
                  distanceInM = 1000D
                )
              ),
              beamVehicleId = Id.createVehicleId("car"),
              Id.create("car", classOf[BeamVehicleType]),
              asDriver = true,
              cost = 0.0,
              unbecomeDriverOnCompletion = true
            )
          )
        )
      ),
      requestId = 1,
      None,
      true
    )
  }

  private def getSkimMock(skim: Skim): ODSkims = {
    val odSkims = mock[ODSkims]
    when(
      odSkims.getTimeDistanceAndCost(
        any[Location],
        any[Location],
        any[Int],
        any[BeamMode],
        any[Id[BeamVehicleType]],
        any[BeamVehicleType],
        any[Double],
        any[BeamScenario],
        any[Option[Id[TAZ]]],
        any[Option[Id[TAZ]]]
      )
    ).thenReturn(skim)
    odSkims
  }

  def carRoutingResponse: RoutingResponse = createRoutingResponse(BeamMode.CAR)

  def walkRoutingResponse: RoutingResponse = createRoutingResponse(BeamMode.WALK)
}
