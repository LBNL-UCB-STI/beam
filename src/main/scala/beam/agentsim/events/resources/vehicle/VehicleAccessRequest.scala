package beam.agentsim.events.resources.vehicle

import java.time.Period

import akka.actor.ActorRef
import beam.agentsim.User
import beam.agentsim.agents.vehicles.VehicleData
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.ReservationErrorCode.ReservationErrorCode
import beam.agentsim.events.resources._
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id

/**
  * @author dserdiuk
  */
case class VehicleAccessRequest(userId: Id[User], timePeriod: Period,
                                requestLocation: SpaceTime, modes: Vector[String])

case class ReservationRequest(requestId: Id[ReservationRequest], departFrom: BeamLeg, arriveAt: BeamLeg, passenger: ActorRef)
case class ReservationRequestWithVehicle(request: ReservationRequest, vehicleData: VehicleData)

case class ReservationResponse(requestId: Id[ReservationRequest], response: Either[ReservationError, ReserveConfirmInfo])

case class ReserveConfirmInfo(departFrom: BeamLeg, arriveAt: BeamLeg, passenger: ActorRef, vehicleAgent: ActorRef) {
  def this(r: ReservationRequest, vehicleAgent: ActorRef) = this(r.departFrom, r.arriveAt, r.passenger, vehicleAgent)
}

case object VehicleUnavailable extends ReservationError {
  override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
}
case object VehicleGone extends ReservationError {
  override def errorCode: ReservationErrorCode = ReservationErrorCode.ResourceUnAvailable
}