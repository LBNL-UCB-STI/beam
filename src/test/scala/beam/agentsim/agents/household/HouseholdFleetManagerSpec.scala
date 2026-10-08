package beam.agentsim.agents.household

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HouseholdFleetManagerSpec extends AnyWordSpec with Matchers {
  "duplicateDriverDiagnostic" should {
    "retain the vehicle, ownership, requester, trigger, and availability context" in {
      HouseholdFleetManager.duplicateDriverDiagnostic(
        vehicleId = "vehicle-42",
        currentDriver = "akka://ClusterSystem/user/population/driver-1",
        fleetManager = "akka://ClusterSystem/user/population/household-7/Passenger-Car",
        requester = "akka://ClusterSystem/user/population/person-2",
        personId = "person-2",
        triggerId = 17L,
        availableVehicleIds = Seq("vehicle-42", "vehicle-99")
      ) shouldBe
      "Duplicate household vehicle assignment: vehicle=vehicle-42, " +
      "existingDriver=akka://ClusterSystem/user/population/driver-1, " +
      "fleetManager=akka://ClusterSystem/user/population/household-7/Passenger-Car, " +
      "requester=akka://ClusterSystem/user/population/person-2, person=person-2, " +
      "triggerId=17, availableVehicleIds=[vehicle-42, vehicle-99]"
    }
  }
}
