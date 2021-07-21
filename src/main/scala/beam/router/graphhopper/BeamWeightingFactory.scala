package beam.router.graphhopper

import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER
import com.graphhopper.routing.weighting.{DefaultTurnCostProvider, Weighting}
import com.graphhopper.routing.weighting.Weighting.INFINITE_U_TURN_COSTS
import com.graphhopper.storage.GraphHopperStorage
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters.Routing

class BeamWeightingFactory(
  wayId2TravelTime: Map[Long, Double],
  ghStorage: GraphHopperStorage,
  encodingManager: EncodingManager
) extends DefaultWeightingFactory(ghStorage, encodingManager) {

  override def createWeighting(profile: Profile, requestHints: PMap, disableTurnCosts: Boolean): Weighting = {
    if (profile.getWeighting == BeamWeighting.Name) {
      createBeamWeighting(profile, requestHints, disableTurnCosts)
    } else {
      super.createWeighting(profile, requestHints, disableTurnCosts)
    }
  }

  private def createBeamWeighting(profile: Profile, requestHints: PMap, disableTurnCosts: Boolean) = {
    val hints = new PMap
    hints.putAll(profile.getHints)
    hints.putAll(requestHints)

    val encoder = encodingManager.getEncoder(profile.getVehicle)
    val turnCostProvider = if (profile.isTurnCosts && !disableTurnCosts) {
      if (!encoder.supportsTurnCosts)
        throw new IllegalArgumentException("Encoder " + encoder + " does not support turn costs")
      val uTurnCosts = hints.getInt(Routing.U_TURN_COSTS, INFINITE_U_TURN_COSTS)
      new DefaultTurnCostProvider(encoder, ghStorage.getTurnCostStorage, uTurnCosts)
    } else {
      NO_TURN_COST_PROVIDER
    }

    new BeamWeighting(encoder, turnCostProvider, wayId2TravelTime)
  }
}
