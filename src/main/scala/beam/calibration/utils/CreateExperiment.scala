package beam.calibration.utils

import java.io.File

import beam.calibration.SigoptExperimentData
import beam.experiment.{ExperimentApp, ExperimentDef}
import com.sigopt.Sigopt
import com.typesafe.scalalogging.LazyLogging

object CreateExperiment extends ExperimentApp {
  private val NEW_EXPERIMENT_FLAG = "00000"

  // Store CLI inputs as private members
  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  override def main(args: Array[String]): Unit = {
    val experimentLoc: String =
      "production/application-sfbay/calibration/experiment_modes_calibration.yml"
    val benchmarkLoc: String = "production/application-sfbay/calibration/benchmark.csv"
    SigoptExperimentData(experimentDef, benchmarkLoc, NEW_EXPERIMENT_FLAG, development = false)
  }

}
