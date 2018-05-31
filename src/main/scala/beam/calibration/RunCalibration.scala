package beam.calibration

import beam.sim.BeamHelper
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError

object RunCalibration extends App with BeamHelper {

  private val EXPERIMENTS_TAG = "experiments"
  private val BENCHMARK_EXPERIMENTS_TAG = "benchmark"
  private val NUM_ITERATIONS_TAG = "num_iters"

  val argsMap = parseArgs(args)

  if (System.getenv("SIGOPT_DEV_ID") != null) Sigopt.clientToken = System.getenv("SIGOPT_CLIENT_ID")
  else throw new APIConnectionError("Correct developer client token must be present in environment as SIGOPT_CLIENT_ID")

  private val experimentLoc: String = argsMap(EXPERIMENTS_TAG)
  private val benchmarkLoc: String = argsMap(BENCHMARK_EXPERIMENTS_TAG)
  private val numIters: Int = argsMap(NUM_ITERATIONS_TAG).toInt

  private implicit val experimentData: SigoptExperimentData = SigoptExperimentData(experimentLoc, benchmarkLoc, development = false)

  private val experimentRunner: ExperimentRunner = ExperimentRunner()

  experimentRunner.runExperiment(numIters)

  // METHODS //

  def parseArgs(args: Array[String]) = {
    args.sliding(2, 2).toList.collect {
      case Array("--experiments", filePath: String) if filePath.trim.nonEmpty => (EXPERIMENTS_TAG, filePath)
      case Array("--benchmark", filePath: String) if filePath.trim.nonEmpty => (BENCHMARK_EXPERIMENTS_TAG, filePath)
      case Array("--num_iters", numIters: String) if numIters.trim.nonEmpty =>
        (NUM_ITERATIONS_TAG, numIters)
      case arg@_ =>
        throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }


}
