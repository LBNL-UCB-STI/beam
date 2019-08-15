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
     * Pooling with timeout zero or non-pooling with non-zero don't mix yet
     */
    if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name
          .equals("POOLING_ALONSO_MORA") && beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds == 0) {
      throw new RuntimeException(
        "PoolingAlonsoMora is not yet compatible with a parameter value of 0 for requestBufferTimeoutInSeconds. Either make that parameter non-zero or use DEFAULT_MANAGER for the allocationManager."
      )
    } else if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.name
                 .equals("DEFAULT_MANAGER") && beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0) {
      throw new RuntimeException(
        "AllocationManager DEFAULT_MANAGER is not yet compatible with a non-zero parameter value for requestBufferTimeoutInSeconds. Either make that parameter zero or use POOLING_ALONSO_MORA for the allocationManager."
      )
    }

    if (beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds != 0 &&
        beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow > beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds) {
      throw new RuntimeException(
        "Scheduler Parallelism Window must be less than Request Buffer Timeout"
      )
    }

    if (beamServices.beamConfig.beam.physsim.writeRouteHistoryInterval < 0) {
      throw new RuntimeException(
        "Wrong value of Route History file writing iteration"
      )
    }
  }
}
