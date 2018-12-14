package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

object VehicleProtocol {

  sealed trait BecomeDriverResponse

  case class DriverAlreadyAssigned(currentDriver: ActorRef) extends BecomeDriverResponse

  case class RemovePassengerFromTrip(passId: VehiclePersonId)

  case class StreetVehicle(id: Id[Vehicle], locationUTM: SpaceTime, mode: BeamMode, asDriver: Boolean)

  case object BecomeDriverOfVehicleSuccess extends BecomeDriverResponse

  case object NewDriverAlreadyControllingVehicle extends BecomeDriverResponse

}
