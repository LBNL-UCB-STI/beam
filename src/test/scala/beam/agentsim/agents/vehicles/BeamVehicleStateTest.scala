package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import org.matsim.api.core.v01.Coord
import org.scalatest.{FunSuite, Matchers}

class BeamVehicleStateTest extends FunSuite with Matchers {
  test("haveEnoughFuel return true in case vehicle remaining distance >= marginFactor * distance to travel") {
    val bvs = BeamVehicleState(
      primaryFuelLevel = 100,
      secondaryFuelLevel = None,
      remainingPrimaryRangeInM = 100,
      remainingSecondaryRangeInM = None,
      driver = None,
      stall = None
    )
    // Distance between (50, 50) and (10, 10) is 56.5685.
    // Total distance = 56.5685 + 10 (travelDistance) + 10 (distanceToDepot) = 76.5685 * 1 (marginFactor) <= 100 (remainingPrimaryRangeInM)
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(10, 10), 10, 10, 1) shouldBe true
    // Total distance = 56.5685 + 10 (travelDistance) + 10 (distanceToDepot) = 76.5685 * 0 (marginFactor) <= 100 (remainingPrimaryRangeInM)
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(10, 10), 10, 10, 0) shouldBe true

    // Case when the same location
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(50, 50), 10, 10, 1) shouldBe true
  }

  test("haveEnoughFuel return false in case vehicle remaining distance < marginFactor * distance to travel") {
    val bvs = BeamVehicleState(
      primaryFuelLevel = 100,
      secondaryFuelLevel = None,
      remainingPrimaryRangeInM = 100,
      remainingSecondaryRangeInM = None,
      driver = None,
      stall = None
    )
    // Distance between (50, 50) and (10, 10) is 56.5685.
    // Total distance = 56.5685 + 10 (travelDistance) + 10 (distanceToDepot) = 76.5685 * 2 (marginFactor) > 100 (remainingPrimaryRangeInM)
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(10, 10), 10, 10, 2) shouldBe false
    // Total distance = 56.5685 + 10 (travelDistance) + 10 (distanceToDepot) = 76.5685 * 3 (marginFactor) > 100 (remainingPrimaryRangeInM)
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(10, 10), 10, 10, 3) shouldBe false

    // Case when the same location (10 (travelDistance) + 10 (distanceToDepot)) * 5.1 = 102 > 100 (remainingPrimaryRangeInM)
    BeamVehicleState.haveEnoughFuel(bvs, new Coord(50, 50), new Coord(50, 50), 10, 10, 5.1) shouldBe false
  }
}
