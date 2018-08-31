package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailManager.TravelProposal
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger

case class RideHailResponse(
  request: RideHailRequest,
  travelProposal: Option[TravelProposal],
  error: Option[ReservationError] = None,
  triggersToSchedule: Vector[ScheduleTrigger] = Vector()
) {
  override def toString(): String =
    s"request: ${request}, error: ${error}, travelProposal: ${travelProposal}"
}

object RideHailResponse {
  val DUMMY = RideHailResponse(RideHailRequest.DUMMY, None, None)

  def dummyWithError(error: ReservationError) =
    RideHailResponse(RideHailRequest.DUMMY, None, Some(error))

}
