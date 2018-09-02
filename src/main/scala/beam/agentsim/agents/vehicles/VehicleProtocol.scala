package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

object VehicleProtocol {

  case object BecomeDriverOfVehicleSuccessAck

  case class DriverAlreadyAssigned(vehicleId: Id[Vehicle], currentDriver: ActorRef)

  case class RemovePassengerFromTrip(passId: VehiclePersonId)

  case class StreetVehicle(id: Id[Vehicle], location: SpaceTime, mode: BeamMode, asDriver: Boolean)

}
