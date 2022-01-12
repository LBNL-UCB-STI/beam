package beam.utils

import beam.agentsim.agents.vehicles.BeamVehicleType
import org.matsim.api.core.v01.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class InputConsistencyCheckSpec extends AnyWordSpecLike with Matchers {

  def createId(id: String): Id[BeamVehicleType] = Id.create(id, classOf[BeamVehicleType])

  "InputConsistencyCheck" should {
    "verify vehicle types for ridehail type id" in {
      val vehicleTypes = Set(createId("one"), createId("two"))
      val vehicleTypesStr = vehicleTypes.mkString(",")

      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, "one", "two") shouldBe List()
      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, "rh", "two") shouldBe List(
        s"beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId 'rh' is not in vehicleTypes [$vehicleTypesStr]"
      )
      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, "rh", "dummy") shouldBe List(
        s"beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId 'rh' is not in vehicleTypes [$vehicleTypesStr]",
        s"beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId 'dummy' is not in vehicleTypes [$vehicleTypesStr]"
      )
    }

  }

}
