package beam.agentsim.agents.ridehail

sealed trait RideHailRequestType

case object RideHailInquiry extends RideHailRequestType

case object ReserveRide extends RideHailRequestType
