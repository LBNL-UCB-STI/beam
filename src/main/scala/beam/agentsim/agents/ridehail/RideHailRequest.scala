package beam.agentsim.agents.ridehail

import beam.agentsim.agents.vehicles.VehiclePersonId
import beam.router.BeamRouter.Location
import org.apache.commons.lang.builder.HashCodeBuilder
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

case class RideHailRequest(
  requestType: RideHailRequestType,
  customer: VehiclePersonId,
  pickUpLocation: Location,
  departAt: Int,
  destination: Location,
  asPooled: Boolean = false
) {

  /**
    * Returns a unique identifiable value based on the fields. Field requestType should not be part of the hash.
    *
    * @return hashCode(customer, pickUpLocation, departAt, destination)
    */
  lazy val requestId: Int = {
    new HashCodeBuilder()
      .append(customer)
      .append(pickUpLocation)
      .append(departAt)
      .append(destination)
      .toHashCode
  }

  override def toString: String =
    s"id: $requestId, type: $requestType, customer: ${customer.personId}, pickup: $pickUpLocation, time: $departAt, dest: $destination"
}

object RideHailRequest {

  val DUMMY = RideHailRequest(
    RideHailInquiry,
    VehiclePersonId(Id.create("dummy", classOf[Vehicle]), Id.create("dummy", classOf[Person])),
    new Coord(Double.NaN, Double.NaN),
    Int.MaxValue,
    new Coord(Double.NaN, Double.NaN)
  )
}
