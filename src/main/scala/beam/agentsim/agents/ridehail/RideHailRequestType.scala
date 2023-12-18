package beam.agentsim.agents.ridehail

sealed trait RideHailRequestType

case object RideHailInquiry extends RideHailRequestType

case class ReserveRide(rideHailManagerName: String) extends RideHailRequestType
