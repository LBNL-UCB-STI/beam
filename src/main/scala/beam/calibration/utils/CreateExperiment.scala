package beam.calibration.utils

import beam.calibration.{SigoptExperimentData}
import com.sigopt.Sigopt
import com.typesafe.scalalogging.LazyLogging

object CreateExperiment extends LazyLogging {
  private val NEW_EXPERIMENT_FLAG = "00000"

  // Store CLI inputs as private members
  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  def main(args: Array[String]): Unit = {
    val experimentLoc: String = "test/input/sf-light/sf-light-calibration/experiment.yml"
    val benchmarkLoc: String = "test/input/sf-light/sf-light-calibration/benchmark.csv"
    SigoptExperimentData(experimentLoc, benchmarkLoc, NEW_EXPERIMENT_FLAG, development = false)
  }
}
