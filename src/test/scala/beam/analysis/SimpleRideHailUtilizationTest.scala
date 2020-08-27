package beam.analysis

import beam.agentsim.events.PathTraversalEvent
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.scalatest.{FunSuite, Matchers}

class SimpleRideHailUtilizationTest extends FunSuite with Matchers {

  val pathTraversalEvent: PathTraversalEvent = PathTraversalEvent(
    time = 1,
    vehicleId = Id.createVehicleId("rideHailVehicle-1"),
    driverId = "driver-id",
    vehicleType = "CAR",
    seatingCapacity = 0,
    standingRoomCapacity = 0,
    primaryFuelType = "",
    secondaryFuelType = "",
    numberOfPassengers = 0,
    departureTime = 0,
    arrivalTime = 1,
    mode = BeamMode.CAR,
    legLength = 1,
    linkIds = IndexedSeq.empty,
    linkTravelTime = IndexedSeq.empty,
    startX = 0,
    startY = 0,
    endX = 1,
    endY = 1,
    primaryFuelConsumed = 2,
    secondaryFuelConsumed = 2,
    endLegPrimaryFuelLevel = 3,
    endLegSecondaryFuelLevel = 4,
    amountPaid = 1,
    None,
    None
  )

  test("Should ignore non-ridehail vehicles") {
    val rhu = new SimpleRideHailUtilization
    rhu.processStats(pathTraversalEvent.copy(vehicleId = Id.createVehicleId(1)))
    rhu.getStat(0) shouldBe None
  }

  test("Should process ridehail vehicles") {
    val rhu = new SimpleRideHailUtilization
    rhu.getStat(0) shouldBe None

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 0)
    )
    rhu.getStat(0) shouldBe Some(1)

    rhu.getStat(1) shouldBe None

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 1)
    )
    rhu.getStat(1) shouldBe Some(1)

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 1)
    )
    rhu.getStat(1) shouldBe Some(2)

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 1)
    )
    rhu.getStat(1) shouldBe Some(3)

    rhu.getStat(2) shouldBe None

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 2)
    )
    rhu.getStat(2) shouldBe Some(1)

    rhu.processStats(
      pathTraversalEvent.copy(numberOfPassengers = 2)
    )
    rhu.getStat(2) shouldBe Some(2)
  }
}
