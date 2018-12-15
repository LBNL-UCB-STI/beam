package beam.sim.vehiclesharing
import akka.actor.{Actor, ActorLogging}
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import org.matsim.api.core.v01.Id

class InexhaustibleSharedVehicleFleet extends Actor with ActorLogging {

  var nextVehicleIndex = 0

  override def receive: Receive = {
    case MobilityStatusInquiry(whenWhere) =>
      val vehicle = new BeamVehicle(
        Id.createVehicleId("inexhaustible-shared-vehicle-fleet-"+nextVehicleIndex),
        new Powertrain(0.0),
        None,
        BeamVehicleType.defaultCarBeamVehicleType
      )
      nextVehicleIndex += 1
      vehicle.manager = Some(self)
      vehicle.exclusiveAccess = true
      vehicle.spaceTime = whenWhere
      sender ! MobilityStatusResponse(Vector(vehicle))
    case ReleaseVehicle(_) =>
      // That's fine, nothing to do.
  }

}
