package beam.agentsim.events.resources

import java.time.Period

import akka.actor.ActorRef
import beam.router.model.BeamLeg
import enumeratum._
import org.matsim.api.core.v01.Id

import scala.collection.immutable

/**
  * @author dserdiuk
  */

trait AccessInfo {
  def resource: Option[ActorRef]

  def pointOfAccess: BeamLeg

  def releasePoint(): BeamLeg
}

trait AccessRequest {

  def timePeriod: Period

  def requestLocation: BeamLeg
}

trait AccessResponse {

  def accessInformation: Vector[AccessInfo]

}

trait ReservationRequest extends AccessRequest {
  def resource: ActorRef

  def requestId: Id[ReservationRequest]
}

trait ReservationResponse {

  def response: Either[AccessInfo, AccessResponse]

}

trait ReservationError {
  def errorCode: ReservationErrorCode
}

sealed abstract class ReservationErrorCode extends EnumEntry

//  val RideHailNotRequested = Value("RideHailNotRequested")

case object ReservationErrorCode extends Enum[ReservationErrorCode] {

  val values: immutable.IndexedSeq[ReservationErrorCode] = findValues

  case object UnknownInquiryId extends ReservationErrorCode

  case object RideHailVehicleTaken extends ReservationErrorCode

  case object RideHailNotRequested extends ReservationErrorCode

  case object UnknownRideHailReservation extends ReservationErrorCode

  case object RideHailRouteNotFound extends ReservationErrorCode

  case object ResourceUnavailable extends ReservationErrorCode

  case object ResourceCapacityExhausted extends ReservationErrorCode

  case object ResourceFull extends ReservationErrorCode

  case object VehicleNotUnderControl extends ReservationErrorCode

  case object MissedTransitPickup extends ReservationErrorCode

}
