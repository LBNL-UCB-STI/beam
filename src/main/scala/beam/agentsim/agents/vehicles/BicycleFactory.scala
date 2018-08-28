package beam.agentsim.agents.vehicles
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.Person
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters

class BicycleFactory(scenario: Scenario) {

  /**
    * Utility method preparing BEAM to add bicycles as part of mobsim
    */
  def bicyclePrepareForSim(): Unit = {
    // Add the bicycle as a vehicle type here
    implicit val vehicles: Vehicles = scenario.getVehicles
    vehicles.addVehicleType(BeamVehicleType.getBicycleType().toMatsimVehicleType)

    // Add bicycles to household (all for now)
    JavaConverters
      .collectionAsScalaIterable(scenario.getHouseholds.getHouseholds.values())
      .seq
      .foreach { hh =>
        addBicycleVehicleIdsToHousehold(hh)
      }
  }

  def addBicycleVehicleIdsToHousehold(household: Household)(implicit vehicles: Vehicles): Unit = {
    val householdMembers: Iterable[Id[Person]] =
      JavaConverters.collectionAsScalaIterable(household.getMemberIds)

    householdMembers.foreach { id: Id[Person] =>
      val bicycleId: Id[Vehicle] = BeamVehicleType.createId(id)
      household.getVehicleIds.add(bicycleId)

      vehicles.addVehicle(BeamVehicleType.createMatsimVehicle(bicycleId))
    }
  }
}
