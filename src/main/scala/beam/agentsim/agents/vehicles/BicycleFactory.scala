package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters._
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
      (
        Id.create(beamVehicleType.id, classOf[BeamVehicleType]),
        beamVehicleType
      )
    )

    // Add bicycles to household (all for now)

    scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .seq
      .foreach { hh =>
        addBicycleVehicleIdsToHousehold(hh, beamVehicleType)
      }
  }

  def addBicycleVehicleIdsToHousehold(household: Household, beamVehicleType: BeamVehicleType)(
    implicit vehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
  ): Unit = {
    val householdMembers: Iterable[Id[Person]] =
      household.getMemberIds.asScala

    householdMembers.foreach { id: Id[Person] =>
      val bicycleId: Id[BeamVehicle] = BeamVehicle.createId(id, Some("bike"))
      household.getVehicleIds.add(bicycleId)

      val powertrain = Option(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)
        .map(new Powertrain(_))
        .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

      vehicles += (
        (
          bicycleId,
          new BeamVehicle(
            bicycleId,
            powertrain,
            beamVehicleType
          )
        )
      )

    }
  }
}
