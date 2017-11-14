package beam.agentsim.events.resources.vehicle

import beam.agentsim.agents.vehicles.{PassengerSchedule, VehiclePersonId}
import beam.agentsim.events.resources.ReservationErrorCode._
import beam.agentsim.events.resources._
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.RoutingModel.BeamLeg
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk
  */
object Reservation {
  def nextReservationId: Id[ReservationRequest] = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[ReservationRequest])
}

case class ReservationRequest(requestId: Id[ReservationRequest], departFrom: BeamLeg, arriveAt: BeamLeg, passengerVehiclePersonId: VehiclePersonId) {
  def this(departFrom: BeamLeg, arriveAt: BeamLeg, passengerVehiclePersonId: VehiclePersonId) = this(Reservation.nextReservationId, departFrom, arriveAt, passengerVehiclePersonId)
}

case class ReservationRequestWithVehicle(request: ReservationRequest, vehicleIdToReserve: Id[Vehicle])

case class ModifyPassengerSchedule(updatedPassengerSchedule: PassengerSchedule, msgId: Option[Id[_]] = None)

case class ModifyPassengerScheduleAck(msgId: Option[Id[_]] = None)

case class ReservationResponse(requestId: Id[ReservationRequest], response: Either[ReservationError, ReserveConfirmInfo])

case class ReserveConfirmInfo(departFrom: BeamLeg, arriveAt: BeamLeg, passengerVehiclePersonId: VehiclePersonId, triggersToSchedule: Vector[ScheduleTrigger] = Vector())

case object RideHailVehicleTakenError extends ReservationError {
  override def errorCode: ReservationErrorCode = RideHailVehicleTaken
}

case object UnknownRideHailReservationError extends ReservationError {
  override def errorCode: ReservationErrorCode = UnknownRideHailReservation
}


case object UnknownInquiryIdError extends ReservationError {
  override def errorCode: ReservationErrorCode = UnknownInquiryId
}


case object CouldNotFindRouteToCustomer extends ReservationError {
  override def errorCode: ReservationErrorCode = RideHailRouteNotFound
}

case object VehicleGone extends ReservationError {
  override def errorCode: ReservationErrorCode = ResourceUnavailable
}


