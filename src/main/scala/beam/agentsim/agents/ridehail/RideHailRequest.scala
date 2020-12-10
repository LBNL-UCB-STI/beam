package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.utils.RideHailRequestIdGenerator
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}

case class RideHailRequest(
  requestType: RideHailRequestType,
  customer: PersonIdWithActorRef,
  pickUpLocationUTM: Location,
  departAt: Int,
  destinationUTM: Location,
  asPooled: Boolean = false,
  groupedWithOtherRequests: List[RideHailRequest] = List(),
  requestId: Int = RideHailRequestIdGenerator.nextId
) {

  def addSubRequest(subRequest: RideHailRequest): RideHailRequest =
    this.copy(requestId = this.requestId, groupedWithOtherRequests = this.groupedWithOtherRequests :+ subRequest)
  override def equals(that: Any): Boolean = this.requestId == that.asInstanceOf[RideHailRequest].requestId
  override def hashCode: Int = requestId
  override def toString: String =
    s"RideHailRequest(id: $requestId, type: $requestType, customer: ${customer.personId}, pickup: $pickUpLocationUTM, time: $departAt, dest: $destinationUTM)"
}

object RideHailRequest {

  val DUMMY: RideHailRequest = RideHailRequest(
    RideHailInquiry,
    PersonIdWithActorRef(Id.create("dummy", classOf[Person]), ActorRef.noSender),
    new Coord(Double.NaN, Double.NaN),
    Int.MaxValue,
    new Coord(Double.NaN, Double.NaN)
  )

  /**
    * Converts the request's pickup and drop coordinates from MATSIM to R5 edge to avoid impression
    * @param request ridehail request
    * @param beamServices an instance of beam services
    */
  def handleImpression(request: RideHailRequest, beamServices: BeamServices): RideHailRequest = {
    val pickUpLocUpdatedUTM = beamServices.geo.wgs2Utm(
      beamServices.geo.snapToR5Edge(
        beamServices.beamScenario.transportNetwork.streetLayer,
        beamServices.geo.utm2Wgs(request.pickUpLocationUTM)
      )
    )
    val destLocUpdatedUTM = beamServices.geo.wgs2Utm(
      beamServices.geo.snapToR5Edge(
        beamServices.beamScenario.transportNetwork.streetLayer,
        beamServices.geo.utm2Wgs(request.destinationUTM)
      )
    )
    request.copy(destinationUTM = destLocUpdatedUTM, pickUpLocationUTM = pickUpLocUpdatedUTM)
  }
}
