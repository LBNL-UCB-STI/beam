package beam.utils

import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicleType, FuelType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.sim.{CircularGeofence, Geofence}
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class SpatialIndexForRideHailAgentLocationTest extends AnyFunSuite with Matchers {
  // #####################################################################################
  // If one day this test breaks then most probably it is because of the changes in `RideHailAgentLocation.equals`
  // #####################################################################################
  test("Should not allow to put multiple RideHailAgentLocation with the same vehicleId") {
    val spatialIndex = new QuadTree[RideHailAgentLocation](0, 0, 1000, 100)
    val rideHailAgentLocation = RideHailAgentLocation(
      null,
      Id.createVehicleId("1"),
      BeamVehicleType(
        Id.create("car", classOf[BeamVehicleType]),
        1,
        1,
        3,
        FuelType.Gasoline,
        0.1,
        0.1,
        vehicleCategory = VehicleCategory.Car
      ),
      SpaceTime(1, 1, 1)
    )

    spatialIndex.put(10, 10, rideHailAgentLocation)
    spatialIndex.size() shouldBe (1)

    val updatedRideHailAgentLocation = rideHailAgentLocation.copy(latestUpdatedLocationUTM = SpaceTime(2, 2, 4))
    spatialIndex.put(10, 10, updatedRideHailAgentLocation)
    spatialIndex.size() shouldBe (1)

    spatialIndex.getDisk(0, 0, 20).asScala.head shouldBe (rideHailAgentLocation)
  }

  test("Remove should respect only `RideHailAgentLocation.vehicleId`") {
    // If one day this test breaks it that `RideHailAgentLocation.equals` has been changed
    val spatialIndex = new QuadTree[RideHailAgentLocation](0, 0, 1000, 100)
    val rideHailAgentLocation = RideHailAgentLocation(
      null,
      Id.createVehicleId("1"),
      BeamVehicleType(
        Id.create("car", classOf[BeamVehicleType]),
        1,
        1,
        3,
        FuelType.Gasoline,
        0.1,
        0.1,
        vehicleCategory = VehicleCategory.Car
      ),
      SpaceTime(1, 1, 1)
    )
    spatialIndex.put(10, 10, rideHailAgentLocation)

    val updatedRideHailAgentLocation = rideHailAgentLocation.copy(
      latestUpdatedLocationUTM = SpaceTime(2, 2, 4),
      vehicleType = rideHailAgentLocation.vehicleType.copy(seatingCapacity = 123),
      geofence = Some(CircularGeofence(1, 2, 3))
    )
    spatialIndex.remove(10, 10, updatedRideHailAgentLocation)
    spatialIndex.size() shouldBe (0)
  }
}
