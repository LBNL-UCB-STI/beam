package beam.utils.scripts

import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

object FailFast extends LazyLogging {

  def run(beamServices: BeamServices): Unit = {

    /*
     * ModeChoiceLCCM
     * -- replanning delete strategy, tryToKeepOneOfEachClass, only relevant to LCCM
     * -- vice versa of above, LCCM requires tryToKeepOneOfEachClass
     */
    if (beamServices.beamConfig.matsim.modules.strategy.planSelectorForRemoval.equals("tryToKeepOneOfEachClass") &&
        !beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equals("ModeChoiceLCCM")) {
      throw new RuntimeException(
        "The replanning deletion strategy 'tryToKeepOneOfEachClass' must only be used along with the 'ModeChoiceLCCM' mode choice class. In other words, if the parameter beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass!=ModeChoiceLCCM then beamConfig.matsim.modules.strategy.planSelectorForRemoval != 'tryToKeepOneOfEachClass'"
      )
    }
    if (!beamServices.beamConfig.matsim.modules.strategy.planSelectorForRemoval.equals("tryToKeepOneOfEachClass") &&
        beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equals("ModeChoiceLCCM")) {
      throw new RuntimeException(
        "The replanning deletion strategy 'tryToKeepOneOfEachClass' must be used along with the 'ModeChoiceLCCM' mode choice class. In other words, if the parameter beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass==ModeChoiceLCCM then beamConfig.matsim.modules.strategy.planSelectorForRemoval == 'tryToKeepOneOfEachClass'"
      )
    }

    /*
     * Pooling not ready yet
     */
    if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name.equals("POOLING")) {
      throw new RuntimeException(
        "Pooling, while a class in the code base, is not ready for use yet. In other words, please do not set beamConfig.beam.agentsim.agents.rideHail.allocationManager.name == \"POOLING\""
      )
    }

    /*
     * Ride Hail Allocation
     * -- do not enable both ride hail request buffering and repositioning at the same time
     */
    if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0 &&
        beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds > 0) {
      throw new RuntimeException(
        "Ride Hail Allocation should only enable repositioning or request buffering or neither, but not both. The ability to enable both is a feature under development. In other words, " +
        "If beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0 then beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds == 0 or " +
        "If beamConfig.beam.agentsim.agents.rideHail.allocationManager.repositionTimeoutInSeconds > 0 then beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds == 0"
      )
    }
  }
}
