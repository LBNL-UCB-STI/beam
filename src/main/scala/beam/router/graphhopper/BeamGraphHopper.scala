package beam.router.graphhopper

import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER
import com.graphhopper.routing.weighting.Weighting.INFINITE_U_TURN_COSTS
import com.graphhopper.routing.weighting.{DefaultTurnCostProvider, Weighting}
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters.Routing

class BeamGraphHopper(wayId2TravelTime: Map[Long, Double]) extends GraphHopper {

  override def createWeighting(profile: Profile, hints: PMap, disableTurnCosts: Boolean): Weighting = {
    if (profile.getWeighting == BeamWeighting.Name) {
      createBeamWeighting(profile, hints, disableTurnCosts)
    } else {
      super.createWeighting(profile, hints, disableTurnCosts)
    }
  }

  private def createBeamWeighting(profile: Profile, requestHints: PMap, disableTurnCosts: Boolean) = {
    val hints = new PMap
    hints.putAll(profile.getHints)
    hints.putAll(requestHints)

    val encoder = getEncodingManager.getEncoder(profile.getVehicle)
    val turnCostProvider = if (profile.isTurnCosts && !disableTurnCosts) {
      if (!encoder.supportsTurnCosts)
        throw new IllegalArgumentException("Encoder " + encoder + " does not support turn costs")
      val uTurnCosts = hints.getInt(Routing.U_TURN_COSTS, INFINITE_U_TURN_COSTS)
      new DefaultTurnCostProvider(encoder, getGraphHopperStorage.getTurnCostStorage, uTurnCosts)
    } else {
      NO_TURN_COST_PROVIDER
    }

    new BeamWeighting(encoder, turnCostProvider, wayId2TravelTime)
  }
}
