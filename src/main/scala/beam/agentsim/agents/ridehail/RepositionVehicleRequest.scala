package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.PassengerSchedule
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class RepositionVehicleRequest(
  passengerSchedule: PassengerSchedule,
  tick: Int,
  vehicleId: Id[Vehicle],
  rideHailAgent: ActorRef
)
