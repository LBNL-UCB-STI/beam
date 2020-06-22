package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailManager.TravelProposal
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.Trigger

case object DelayedRideHailResponse

case class RideHailResponse(
  request: RideHailRequest,
  travelProposal: Option[TravelProposal],
  error: Option[ReservationError] = None,
  triggersToSchedule: Vector[ScheduleTrigger] = Vector()
) {
  override def toString: String =
    s"RideHailResponse(request: $request, error: $error, travelProposal: $travelProposal)"
}

case class RideHailResponseTrigger(tick: Int, rideHailResponse: RideHailResponse) extends Trigger

object RideHailResponse {
  val DUMMY: RideHailResponse = RideHailResponse(RideHailRequest.DUMMY, None, None)

  def dummyWithError(error: ReservationError): RideHailResponse =
    RideHailResponse(RideHailRequest.DUMMY, None, Some(error))

}
