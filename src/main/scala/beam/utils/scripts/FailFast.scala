package beam.utils.scripts

import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging

object FailFast extends LazyLogging {

  def run(beamServices: BeamServices): Unit = {

    val config = beamServices.beamConfig

    /*
     * ModeChoiceLCCM
     * -- replanning delete strategy, tryToKeepOneOfEachClass, only relevant to LCCM
     * -- vice versa of above, LCCM requires tryToKeepOneOfEachClass
     */
    if (config.matsim.modules.strategy.planSelectorForRemoval.equals("tryToKeepOneOfEachClass") &&
        !config.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equals("ModeChoiceLCCM")) {
      throw new RuntimeException(
        "The replanning deletion strategy 'tryToKeepOneOfEachClass' must only be used along with the 'ModeChoiceLCCM' mode choice class. In other words, if the parameter beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass!=ModeChoiceLCCM then beamConfig.matsim.modules.strategy.planSelectorForRemoval != 'tryToKeepOneOfEachClass'"
      )
    }
    if (!config.matsim.modules.strategy.planSelectorForRemoval.equals("tryToKeepOneOfEachClass") &&
        config.beam.agentsim.agents.modalBehaviors.modeChoiceClass.equals("ModeChoiceLCCM")) {
      throw new RuntimeException(
        "The replanning deletion strategy 'tryToKeepOneOfEachClass' must be used along with the 'ModeChoiceLCCM' mode choice class. In other words, if the parameter beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass==ModeChoiceLCCM then beamConfig.matsim.modules.strategy.planSelectorForRemoval == 'tryToKeepOneOfEachClass'"
      )
    }

    /*
     * Pooling with timeout zero or non-pooling with non-zero don't mix yet
     */
    if (config.beam.agentsim.agents.rideHail.allocationManager.name
          .equals("POOLING_ALONSO_MORA") && config.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds == 0) {
      throw new RuntimeException(
        "PoolingAlonsoMora is not yet compatible with a parameter value of 0 for requestBufferTimeoutInSeconds. Either make that parameter non-zero or use DEFAULT_MANAGER for the allocationManager."
      )
    } else if (config.beam.agentsim.agents.rideHail.allocationManager.name
                 .equals("DEFAULT_MANAGER") && config.beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds > 0) {
      throw new RuntimeException(
        "AllocationManager DEFAULT_MANAGER is not yet compatible with a non-zero parameter value for requestBufferTimeoutInSeconds. Either make that parameter zero or use POOLING_ALONSO_MORA for the allocationManager."
      )
    }

    /*
     * We don't expect "Electricity" to be a secondary powertrain type and it can produce unexpected results if set as such. So we fail.
     */

    if (beamServices.beamScenario.vehicleTypes.exists(_._2.secondaryFuelType.contains(Electricity))) {
      val vehicleType = beamServices.beamScenario.vehicleTypes
        .find(_._2.secondaryFuelType.contains(Electricity))
        .get
        ._2
        .id
      throw new RuntimeException(
        s"Found BeamVehicleType $vehicleType with 'Electricity' specified as a FuelType for the secondary powertrain. This is likely a mistake and we are failing so it can be corrected otherwise unexpected behavior will result. For a BEV the primary fuel type should be Electricity and secondary should be empty / blank. For PHEV the primary should be Electricity and secondary should be Gasoline."
      )
    }

    if (config.beam.physsim.writeRouteHistoryInterval < 0) {
      throw new RuntimeException(
        "Wrong value of Route History file writing iteration"
      )
    }
  }
}
