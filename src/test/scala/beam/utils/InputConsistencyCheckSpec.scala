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

      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, Seq("one"), "two") shouldBe List()
      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, Seq("one", "rh"), "two") shouldBe List(
        s"beam.agentsim.agents.rideHail.managers[1].initialization.procedural.vehicleTypeId 'rh' is not in vehicleTypes [$vehicleTypesStr]"
      )
      InputConsistencyCheck.checkVehicleTypes(vehicleTypes, Seq("one", "two", "rh"), "dummy") shouldBe List(
        s"beam.agentsim.agents.rideHail.managers[2].initialization.procedural.vehicleTypeId 'rh' is not in vehicleTypes [$vehicleTypesStr]",
        s"beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId 'dummy' is not in vehicleTypes [$vehicleTypesStr]"
      )
    }

  }

}
