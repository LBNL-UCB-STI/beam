package beam.sim

import org.matsim.core.config.{Config => MatsimConfig}
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.nowarn

trait BeamValidationHelper extends LazyLogging {

  @nowarn
  def ensureRequiredValuesExist(matsimConfig: MatsimConfig): Unit = {
    logger.info("ensureRequiredValuesExist() entry")
    if (Option(matsimConfig.households().getInputFile).forall(_.isEmpty)) {
      throw new RuntimeException(
        "No households input file, please specify 'beam.agentsim.agents.households.inputFilePath' in your config file"
      )
    }

    if (Option(matsimConfig.households().getInputHouseholdAttributesFile).forall(_.isEmpty)) {
      throw new RuntimeException(
        "No households attributes file, please specify 'beam.agentsim.agents.households.inputHouseholdAttributesFilePath' in your config file"
      )
    }

    if (Option(matsimConfig.plans().getInputFile).forall(_.isEmpty)) {
      throw new RuntimeException(
        "No plans input file, please specify 'beam.agentsim.agents.plans.inputPlansFilePath' in your config file"
      )
    }

    if (Option(matsimConfig.plans().getInputPersonAttributeFile).forall(_.isEmpty)) {
      throw new RuntimeException(
        "No plans attributes file, please specify 'beam.agentsim.agents.plans.inputPersonAttributesFilePath' in your config file"
      )
    }
  }
}
