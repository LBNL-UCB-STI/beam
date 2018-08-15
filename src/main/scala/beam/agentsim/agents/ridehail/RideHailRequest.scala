package beam.agentsim.agents.ridehail

import beam.agentsim.agents.vehicles.VehiclePersonId
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.{BeamTime, DiscreteTime}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

case class RideHailRequest(
  requestType: RideHailRequestType,
  customer: VehiclePersonId,
  pickUpLocation: Location,
  departAt: BeamTime,
  destination: Location
) {
  // We make requestId be independent of request type, all that matters is details of the customer
  lazy val requestId: Int =
    this.copy(requestType = RideHailInquiry).hashCode()
}

object RideHailRequest {

  val DUMMY = RideHailRequest(
    RideHailInquiry,
    VehiclePersonId(Id.create("dummy", classOf[Vehicle]), Id.create("dummy", classOf[Person])),
    new Coord(Double.NaN, Double.NaN),
    DiscreteTime(Int.MaxValue),
    new Coord(Double.NaN, Double.NaN)
  )
}
