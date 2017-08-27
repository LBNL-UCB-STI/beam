package beam.agentsim.events.resources.vehicle

import java.time.Period

import beam.agentsim.User
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources._
import beam.router.RoutingModel.BeamLeg
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk
  */
object Reservation {

  def nextReservationId = Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[ReservationRequest])
}
case class ReservationRequest(requestId: Id[ReservationRequest], departFrom: BeamLeg, arriveAt: BeamLeg, passengerVehicle: Id[Vehicle], requesterPerson: Id[Person]) {
  def this(departFrom: BeamLeg, arriveAt: BeamLeg, passenger: Id[Vehicle], requester: Id[Person]) = this(Reservation.nextReservationId, departFrom, arriveAt, passenger, requester)
}

case class ReservationRequestWithVehicle(request: ReservationRequest, vehicleIdToReserve: Id[Vehicle])

case class ReservationResponse(requestId: Id[ReservationRequest], response: Either[ReservationError, ReserveConfirmInfo])

case class ReserveConfirmInfo(departFrom: BeamLeg, arriveAt: BeamLeg, passenger: Id[Vehicle], reservedVehicle:  Id[Vehicle])

case object VehicleUnavailable extends ReservationError {
  override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
}
case object VehicleTooFar extends ReservationError {
  override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
}
case object VehicleGone extends ReservationError {
  override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
}