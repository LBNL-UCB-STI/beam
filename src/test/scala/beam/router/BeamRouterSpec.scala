package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.{Location, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.core.ODSkimmer.Skim
import beam.router.skim.readonly.ODSkims
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.BeamScenarioForTest
import org.matsim.api.core.v01.Id
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatestplus.mockito.MockitoSugar

/**
  * Specs for BeamRouter
  */
class BeamRouterSpec extends FlatSpec with MockitoSugar with BeamScenarioForTest {
  it should "use odSkim travel times for car" in {
    val beamScenario = getBeamScenario("test/input/sf-light/sf-light-0.5k.conf", 1.0)

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
