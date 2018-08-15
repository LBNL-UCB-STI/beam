package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailManager.TravelProposal
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger

case class RideHailResponse(
  request: RideHailRequest,
  travelProposal: Option[TravelProposal],
  error: Option[ReservationError] = None,
  triggersToSchedule: Vector[ScheduleTrigger] = Vector()
)

object RideHailResponse {
  val dummy = RideHailResponse(RideHailRequest.dummy, None, None)

  def dummyWithError(error: ReservationError) =
    RideHailResponse(RideHailRequest.dummy, None, Some(error))
}

