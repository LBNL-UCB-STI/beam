package beam.agentsim.events.resources.vehicle

import java.time.Period

import beam.agentsim.User
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources._
import org.matsim.api.core.v01.Id

/**
  * @author dserdiuk
  */
case class VehicleAccessRequest(userId: Id[User], timePeriod: Period,
                                requestLocation: SpaceTime, modes: Vector[String]) extends AccessRequest

case class VehicleReservationRequest(userId: Id[User], timePeriod: Period,
                                     requestLocation: SpaceTime, resource: BeamVehicle) extends ReservationRequest[BeamVehicle]
case class VehicleAccessResponse(accessInformation: Vector[VehicleAccessInfo]) extends AccessResponse[BeamVehicle]

case class VehicleReservationResponse(response: Either[VehicleAccessInfo, VehicleAccessResponse]) extends ReservationResponse[BeamVehicle]

case class VehicleAccessInfo(resource: Option[BeamVehicle], timePeriodAvailable: Period,
                             pointOfAccess: SpaceTime, mode: String) extends AccessInfo[BeamVehicle]