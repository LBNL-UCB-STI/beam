package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.BeamPath
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.concurrent.Future

object VehicleProtocol {

  case class VehicleLocationRequest(time: Double)

  case class VehicleLocationResponse(vehicleId: Id[Vehicle], spaceTime: Future[SpaceTime])

  case class AlightingConfirmation(vehicleId: Id[Vehicle])

  case class BoardingConfirmation(vehicleId: Id[Vehicle])

  case class BecomeDriver(tick: Double, driver: Id[_], passengerSchedule: Option[PassengerSchedule] = None)

  case class UnbecomeDriver(tick: Double, driver: Id[_])

  case object BecomeDriverOfVehicleSuccessAck

  case class DriverAlreadyAssigned(vehicleId: Id[Vehicle], currentDriver: ActorRef)

  case class BoardVehicle(tick: Double, passengerVehicle: VehiclePersonId)

  case class AlightVehicle(tick: Double, passengerVehicle: VehiclePersonId)

  case class RemovePassengerFromTrip(passId: VehiclePersonId)

  case class StreetVehicle(id: Id[Vehicle], location: SpaceTime, mode: BeamMode, asDriver: Boolean)

  case class AssignedCarrier(carrierVehicleId: Id[Vehicle])

  case class SetCarrier(id: Id[Vehicle])

  case object ResetCarrier

  case class ClearCarrier()

}
