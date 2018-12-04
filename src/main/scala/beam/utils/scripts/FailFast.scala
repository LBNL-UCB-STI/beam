package beam.utils.scripts

import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

object FailFast extends LazyLogging {

  def run(beamServices: BeamServices): Unit = {

    if ((beamServices.beamConfig.matsim.modules.strategy.planSelectorForRemoval.equals("tryToKeepOneOfEachClass"))
        && (!beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equals("ModeChoiceLCCM"))) {
      logger.error(
        "Simulation breaks due to beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass!=ModeChoiceLCCM and beamServices.beamConfig.matsim.modules.strategy.planSelectorForRemoval = " + beamServices.beamConfig.matsim.modules.strategy.planSelectorForRemoval
      )
    }

  }
}
