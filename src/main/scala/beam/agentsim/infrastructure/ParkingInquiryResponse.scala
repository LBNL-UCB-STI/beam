package beam.agentsim.infrastructure

import beam.agentsim.scheduler.HasTriggerId

case class ParkingInquiryResponse(
  stall: ParkingStall,
  requestId: Int,
  triggerId: Long,
  numAvailableChargers: Option[Int] = None
) extends HasTriggerId
