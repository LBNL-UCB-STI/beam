package scripts.calibration.utils

import scripts.experiment.ExperimentApp
import com.sigopt.Sigopt
import scripts.calibration
import scripts.calibration.SigoptExperimentData

object CreateExperiment extends ExperimentApp {
  private val NEW_EXPERIMENT_FLAG = "00000"

  // Store CLI inputs as private members
  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  override def lastThingDoneInMain(): Unit = {
    Sigopt.clientToken = SigOptApiToken.getClientAPIToken
    val benchmarkLoc: String = "production/application-sfbay/calibration/benchmark.csv"
    calibration.SigoptExperimentData(experimentDef, benchmarkLoc, NEW_EXPERIMENT_FLAG)
  }

}
