package beam.agentsim.infrastructure

import beam.agentsim.scheduler.HasTriggerId

case class ParkingInquiryResponse(stall: ParkingStall, requestId: Int, triggerId: Long) extends HasTriggerId
