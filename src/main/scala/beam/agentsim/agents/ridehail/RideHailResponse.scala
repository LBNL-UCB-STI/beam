package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailManager.TravelProposal
import beam.agentsim.events.resources.ReservationError
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.{HasTriggerId, Trigger}

case object DelayedRideHailResponse

case class RideHailResponse(
  request: RideHailRequest,
  travelProposal: Option[TravelProposal],
  rideHailManagerName: String,
  error: Option[ReservationError] = None,
  triggersToSchedule: Vector[ScheduleTrigger] = Vector(),
  directTripTravelProposal: Option[TravelProposal] = None
) extends HasTriggerId {

  def isFailed: Boolean = error.isDefined
  def isSuccessful: Boolean = !isFailed && travelProposal.isDefined

  override def toString: String =
    s"RideHailResponse(request: $request, error: $error, travelProposal: $travelProposal, rhm: ${rideHailManagerName})"

  override def triggerId: Long = request.triggerId
}

case class RideHailResponseTrigger(tick: Int, rideHailResponse: RideHailResponse) extends Trigger

object RideHailResponse {
  val DUMMY: RideHailResponse = RideHailResponse(RideHailRequest.DUMMY, None, "_DUMMY_")

  def dummyWithError(error: ReservationError, request: RideHailRequest = RideHailRequest.DUMMY): RideHailResponse =
    RideHailResponse(request, None, "_DUMMY_WITH_ERROR_", Some(error))

}
