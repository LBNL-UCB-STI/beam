package beam.agentsim.events.resources

import java.time.Period

import beam.agentsim.{Resource, User}
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id

/**
  * @author dserdiuk
  */

trait AccessInfo[R <: Resource]  {
  def resource: Option[R]
  def timePeriodAvailable: Period
  def pointOfAccess: SpaceTime
}

trait AccessRequest {

  def userId: Id[User]

  def timePeriod: Period

  def requestLocation: SpaceTime
}

trait AccessResponse[R <: Resource] {

 def accessInformation: Vector[AccessInfo[R]]

}

trait ReservationRequest[R <: Resource] extends AccessRequest {
  def resource: R
}

trait ReservationResponse[R <: Resource] {

  def response: Either[AccessInfo[R], AccessResponse[R]]

}