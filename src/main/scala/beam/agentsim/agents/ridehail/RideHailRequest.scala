package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailMatching.CustomerRequest
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.agentsim.scheduler.HasTriggerId
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
  withWheelchair: Boolean = false,
  groupedWithOtherRequests: List[RideHailRequest] = List(),
  requestId: Int = RideHailRequestIdGenerator.nextId,
  requestTime: Option[Int] = None,
  quotedWaitTime: Option[Int] = None,
  rideHailServiceSubscription: Seq[String],
  requester: ActorRef,
  triggerId: Long
) extends HasTriggerId {
  def shouldReserveRide: Boolean = requestType == ReserveRide

  def addSubRequest(subRequest: RideHailRequest): RideHailRequest =
    this.copy(requestId = this.requestId, groupedWithOtherRequests = this.groupedWithOtherRequests :+ subRequest)
  def thisRequestWithGroupedRequests: List[RideHailRequest] = this :: groupedWithOtherRequests
  override def equals(that: Any): Boolean = this.requestId == that.asInstanceOf[RideHailRequest].requestId
  override def hashCode: Int = requestId

  override def toString: String =
    s"RideHailRequest(id: $requestId, type: $requestType, customer: ${customer.personId}, pickup: $pickUpLocationUTM, time: $departAt, dest: $destinationUTM)"
}

object RideHailRequest {

  def fromCustomerRequest(customerRequest: CustomerRequest, asPooled: Boolean): RideHailRequest = {
    RideHailRequest(
      ReserveRide,
      customerRequest.person,
      customerRequest.pickup.activity.getCoord,
      customerRequest.pickup.activity.getEndTime.toInt,
      customerRequest.dropoff.activity.getCoord,
      asPooled,
      requester = customerRequest.person.personRef,
      rideHailServiceSubscription = Seq.empty,
      triggerId = customerRequest.triggerId
    )
  }

  val DUMMY: RideHailRequest = RideHailRequest(
    RideHailInquiry,
    PersonIdWithActorRef(Id.create("dummy", classOf[Person]), ActorRef.noSender),
    new Coord(Double.NaN, Double.NaN),
    Int.MaxValue,
    new Coord(Double.NaN, Double.NaN),
    requester = ActorRef.noSender,
    rideHailServiceSubscription = Seq.empty,
    triggerId = -1
  )

  /**
    * Converts the request's pickup and drop coordinates from WGS to UTM
    * @param request ridehail request
    * @param beamServices an instance of beam services
    */
  def projectCoordinatesToUtm(request: RideHailRequest, beamServices: BeamServices): RideHailRequest = {
    val pickUpLocUpdatedUTM: Location = projectCoordinateToUtm(request.pickUpLocationUTM, beamServices)
    val destLocUpdatedUTM: Location = projectCoordinateToUtm(request.destinationUTM, beamServices)
    request.copy(destinationUTM = destLocUpdatedUTM, pickUpLocationUTM = pickUpLocUpdatedUTM)
  }

  def projectCoordinateToUtm(location: Location, beamServices: BeamServices): Location = {
    val linkRadiusMeters = beamServices.beamConfig.beam.routing.r5.linkRadiusMeters
    beamServices.geo.wgs2Utm(
      beamServices.geo.snapToR5Edge(
        beamServices.beamScenario.transportNetwork.streetLayer,
        beamServices.geo.utm2Wgs(location),
        linkRadiusMeters
      )
    )
  }

  def projectWgsCoordinateToUtm(location: Location, beamServices: BeamServices): Location = {
    val linkRadiusMeters = beamServices.beamConfig.beam.routing.r5.linkRadiusMeters
    beamServices.geo.wgs2Utm(
      beamServices.geo.snapToR5Edge(
        beamServices.beamScenario.transportNetwork.streetLayer,
        location,
        linkRadiusMeters
      )
    )
  }
}
