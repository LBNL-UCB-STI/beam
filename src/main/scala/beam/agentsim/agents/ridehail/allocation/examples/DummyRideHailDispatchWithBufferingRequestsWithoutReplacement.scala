package beam.agentsim.agents.ridehail.allocation.examples

import beam.agentsim.agents.rideHail.allocation.examples.DummyRideHailDispatchWithBufferingRequests
import beam.agentsim.agents.ridehail.RideHailManager

class DummyRideHailDispatchWithBufferingRequestsWithoutReplacement(override val rideHailManager: RideHailManager)
    extends DummyRideHailDispatchWithBufferingRequests(rideHailManager) {

  override val enableDummyRidehailReplacement: Boolean = false

}
