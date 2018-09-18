package beam.calibration.utils

import beam.calibration.SigoptExperimentData
import com.sigopt.Sigopt
import com.typesafe.scalalogging.LazyLogging

object CreateExperiment extends LazyLogging {
  private val NEW_EXPERIMENT_FLAG = "00000"

  // Store CLI inputs as private members
  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  def main(args: Array[String]): Unit = {
    val experimentLoc: String =
      "production/application-sfbay/calibration/experiment_modes_and_counts_calibration_intercept_and_network.yml"
    val benchmarkLoc: String = "production/application-sfbay/calibration/benchmark.csv"
    SigoptExperimentData(experimentLoc, benchmarkLoc, NEW_EXPERIMENT_FLAG, development = false)
  }
}
