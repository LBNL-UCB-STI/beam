package beam.agentsim.agents.ridehail

import java.util.UUID

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.VehiclePersonId
import beam.router.BeamRouter.Location
import beam.utils.RideHailRequestIdGenerator
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

case class RideHailRequest(
  requestType: RideHailRequestType,
  customer: VehiclePersonId,
  pickUpLocationUTM: Location,
  departAt: Int,
  destinationUTM: Location,
  asPooled: Boolean = false,
  groupedWithOtherRequests: List[RideHailRequest] = List(),
  requestId: Int = RideHailRequestIdGenerator.nextId
) {

  def addSubRequest(subRequest: RideHailRequest): RideHailRequest =
    this.copy(requestId = this.requestId, groupedWithOtherRequests = this.groupedWithOtherRequests :+ subRequest)
  override def equals(that: Any) = this.requestId == that.asInstanceOf[RideHailRequest].requestId
  override def hashCode: Int = requestId
  override def toString: String =
    s"RideHailRequest(id: $requestId, type: $requestType, customer: ${customer.personId}, pickup: $pickUpLocationUTM, time: $departAt, dest: $destinationUTM)"
}

object RideHailRequest {

  val DUMMY = RideHailRequest(
    RideHailInquiry,
    VehiclePersonId(Id.create("dummy", classOf[Vehicle]), Id.create("dummy", classOf[Person]), ActorRef.noSender),
    new Coord(Double.NaN, Double.NaN),
    Int.MaxValue,
    new Coord(Double.NaN, Double.NaN)
  )
}
