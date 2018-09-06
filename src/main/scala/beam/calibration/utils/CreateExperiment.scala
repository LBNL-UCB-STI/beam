package beam.calibration.utils

import beam.calibration.{SigoptExperimentData}
import com.sigopt.Sigopt
import com.typesafe.scalalogging.LazyLogging


object CreateExperiment extends LazyLogging{


  private val EXPERIMENTS_TAG = "experiments"
  private val BENCHMARK_EXPERIMENTS_TAG = "benchmark"
  private val NUM_ITERATIONS_TAG = "num_iters"
  private val EXPERIMENT_ID_TAG = "experiment_id"
  private val NEW_EXPERIMENT_FLAG = "00000"


  // Store CLI inputs as private members
  Sigopt.clientToken = SigOptApiToken.getClientAPIToken


  //  Context object containing experiment definition

  def createExperiment(experimentLoc: String, benchmarkLoc: String, numIters: Int, experimentId: String) {
    val experimentData: SigoptExperimentData =
      SigoptExperimentData(experimentLoc, benchmarkLoc, experimentId, development = false)
    System.exit(0)
  }

  // Aux Methods //
  def parseArgs(args: Array[String]): Map[String, String] = {
    args
      .sliding(2, 2)
      .toList
      .collect {
        case Array("--experiments", filePath: String) if filePath.trim.nonEmpty =>
          (EXPERIMENTS_TAG, filePath)
        case Array("--benchmark", filePath: String) if filePath.trim.nonEmpty =>
          (BENCHMARK_EXPERIMENTS_TAG, filePath)
        case Array("--num_iters", numIters: String) if numIters.trim.nonEmpty =>
          (NUM_ITERATIONS_TAG, numIters)
        case Array("--experiment_id", experimentId: String) =>
          val trimmedExpId = experimentId.trim
          if (trimmedExpId != "None") { (EXPERIMENT_ID_TAG, trimmedExpId) } else {
            (EXPERIMENT_ID_TAG, NEW_EXPERIMENT_FLAG)
          }
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  def main(args: Array[String]): Unit = {

    val argsMap = parseArgs(args)
    val experimentLoc: String = argsMap(EXPERIMENTS_TAG)
    val benchmarkLoc: String = argsMap(BENCHMARK_EXPERIMENTS_TAG)
    val numIters: Int = argsMap(NUM_ITERATIONS_TAG).toInt
    val experimentId: String = argsMap(EXPERIMENT_ID_TAG)

    createExperiment(experimentLoc, benchmarkLoc, numIters, experimentId);
  }
}

