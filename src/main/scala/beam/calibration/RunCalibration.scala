package beam.calibration

import beam.sim.BeamHelper
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError

object RunCalibration extends App with BeamHelper {

  private val EXPERIMENTS_TAG = "experiments"
  private val BENCHMARK_EXPERIMENTS_TAG = "benchmark"

  val argsMap = parseArgs(args)

  if (System.getenv("SIGOPT_DEV_ID") != null) Sigopt.clientToken = System.getenv("SIGOPT_CLIENT_ID")
  else throw new APIConnectionError("Correct developer client token must be present in environment as SIGOPT_CLIENT_ID")

  private val experimentLoc = argsMap(EXPERIMENTS_TAG)
  private val benchmarkLoc = argsMap(BENCHMARK_EXPERIMENTS_TAG)

  private implicit val experimentData: SigoptExperimentData = SigoptExperimentData(experimentLoc, benchmarkLoc, development = false)
  
  private val experimentRunner: ExperimentRunner = ExperimentRunner()

  experimentRunner.runExperiment(20)

  // METHODS //

  def parseArgs(args: Array[String]) = {
    args.sliding(2, 2).toList.collect {
      case Array("--experiments", filePath: String) if filePath.trim.nonEmpty => (EXPERIMENTS_TAG, filePath)
      case Array("--benchmark", filePath: String) if filePath.trim.nonEmpty => (BENCHMARK_EXPERIMENTS_TAG, filePath)
      case arg@_ =>
        throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }


}
