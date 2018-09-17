package beam.agentsim.agents.vehicles
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.sim.BeamServices
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.Person
import org.matsim.households.Household

import scala.collection.JavaConverters
import scala.collection.concurrent.TrieMap

class BicycleFactory(scenario: Scenario, beamServices: BeamServices) {

  /**
    * Utility method preparing BEAM to add bicycles as part of mobsim
    */
  def bicyclePrepareForSim(): Unit = {
    // Add the bicycle as a vehicle type here
    implicit val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle] = beamServices.privateVehicles

    val beamVehicleType = BeamVehicleType.defaultBicycleBeamVehicleType

    beamServices.vehicleTypes += (
      (Id.create(beamVehicleType.vehicleTypeId, classOf[BeamVehicleType]),
        beamVehicleType)
      )

    // Add bicycles to household (all for now)
    JavaConverters
      .collectionAsScalaIterable(scenario.getHouseholds.getHouseholds.values())
      .seq
      .foreach { hh =>
        addBicycleVehicleIdsToHousehold(hh,beamVehicleType)
      }
  }

  def addBicycleVehicleIdsToHousehold(household: Household, beamVehicleType: BeamVehicleType)(
    implicit vehicles: TrieMap[Id[BeamVehicle], BeamVehicle]): Unit = {
    val householdMembers: Iterable[Id[Person]] =
      JavaConverters.collectionAsScalaIterable(household.getMemberIds)

    householdMembers.foreach { id: Id[Person] =>

      val bicycleId: Id[BeamVehicle] = BeamVehicle.createId(id, Some("bike"))
      household.getVehicleIds.add(bicycleId)

      val powertrain = Option(beamVehicleType.primaryFuelConsumptionInJoule)
        .map(new Powertrain(_))
        .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

      vehicles += ((bicycleId, new BeamVehicle(
        bicycleId,
        powertrain,
        None,
        beamVehicleType,
        None, None
      )))

    }
  }
}
