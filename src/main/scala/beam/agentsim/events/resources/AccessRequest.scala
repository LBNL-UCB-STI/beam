package beam.agentsim.events.resources

import java.time.Period

import akka.actor.ActorRef
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id

/**
  * @author dserdiuk
  */

trait AccessInfo {
  def resource: Option[ActorRef]
  def pointOfAccess: BeamLeg
  def releasePoint: BeamLeg
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
  def errorCode : ReservationErrorCode.ReservationErrorCode
}

object ReservationErrorCode extends Enumeration {
  type ReservationErrorCode = ReservationErrorCode.Value
  val ResourceUnAvailable = Value("NoResourceAvailable")
  val ResourceCapacityExhausted = Value("ResourceCapacityExhausted")

}