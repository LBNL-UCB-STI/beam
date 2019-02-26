package beam.utils.scripts

import beam.sim.BeamServices
import beam.utils.BeamVehicleUtils.readBeamVehicleTypeFile
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap

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

    val vehicleTypeId = beamServices.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId

    val vehicleTypes = {
      TrieMap(
        readBeamVehicleTypeFile(beamServices.beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, null).toList: _*
      )
    }

    if (!vehicleTypes.keys.toString().contains(vehicleTypeId)) {
      throw new RuntimeException(
        "unknown BeamVehicleType or config parameter beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId must be specified"
      )
    }

  }
}
