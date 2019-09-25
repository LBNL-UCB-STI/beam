package beam.agentsim.agents.vehicles

import beam.agentsim.events.resources.ReservationErrorCode._
import beam.agentsim.events.resources._
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.model.BeamLeg
import beam.utils.ReservationRequestIdGenerator

case class ReservationRequest(
  requestId: Int,
  departFrom: BeamLeg,
  arriveAt: BeamLeg,
  passengerVehiclePersonId: PersonIdWithActorRef
)

object ReservationRequest {

  def apply(
    departFrom: BeamLeg,
    arriveAt: BeamLeg,
    passengerVehiclePersonId: PersonIdWithActorRef
  ): ReservationRequest =
    ReservationRequest(
      ReservationRequestIdGenerator.nextId,
      departFrom,
      arriveAt,
      passengerVehiclePersonId
    )
}

case class TransitReservationRequest(fromIdx: Int, toIdx: Int, passenger: PersonIdWithActorRef)

case class ReservationResponse(response: Either[ReservationError, ReserveConfirmInfo])

case class ReserveConfirmInfo(triggersToSchedule: Vector[ScheduleTrigger] = Vector())

case object AccessErrorCodes {

  case object RideHailVehicleTakenError extends ReservationError {
    override def errorCode: ReservationErrorCode = RideHailVehicleTaken
  }

  case object RideHailNotRequestedError extends ReservationError {
    override def errorCode: ReservationErrorCode = RideHailNotRequested
  }

  case object RideHailServiceUnavailableError extends ReservationError {
    override def errorCode: ReservationErrorCode = RideHailNotRequested
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

  case object VehicleGoneError extends ReservationError {
    override def errorCode: ReservationErrorCode = ResourceUnavailable
  }

  case object DriverNotFoundError extends ReservationError {
    override def errorCode: ReservationErrorCode = ResourceUnavailable
  }

  case object VehicleFullError extends ReservationError {
    override def errorCode: ReservationErrorCode = ResourceCapacityExhausted
  }

}
