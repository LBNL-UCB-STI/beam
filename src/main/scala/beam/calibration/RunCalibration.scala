package beam.calibration

import java.io.PrintWriter

import beam.sim.BeamHelper
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError

/**
  To run on t2.large w/ 16g (Max) RAM on AWS via Gradle build script, do the following:
  *
  <code>
 gradle :deploy -PrunName=test-calibration -PinstanceType=t2.large -PmaxRAM=32g -PdeployMode=execute  -PexecuteClass=beam.calibration.RunCalibration -PexecuteArgs="['--experiments', 'test/input/sf-light/sf-light-calibration/experiment.yml', '--benchmark', 'test/input/sf-light/sf-light-calibration/benchmarkTest.csv','--num_iters', '100']"
  </code>
  *
  * For now, to view logging in console, SSH into created instance, and run:
  *
  <code> tail -f /var/log/cloud-init-output.log </code>
  *
  */
object RunCalibration extends App with BeamHelper {

  // Private Constants //
  private val EXPERIMENTS_TAG = "experiments"
  private val BENCHMARK_EXPERIMENTS_TAG = "benchmark"
  private val NUM_ITERATIONS_TAG = "num_iters"

  // Parse the command line inputs
  val argsMap = parseArgs(args)

  // Store CLI inputs as private members
  Sigopt.clientToken = Option { System.getenv("SIGOPT_API_TOKEN") }.getOrElse(
    throw new APIConnectionError(
      "Correct developer client token must be present in environment as SIGOPT_DEV_API Token"
    )
  )
  private val experimentLoc: String = argsMap(EXPERIMENTS_TAG)
  private val benchmarkLoc: String = argsMap(BENCHMARK_EXPERIMENTS_TAG)
  private val numIters: Int = argsMap(NUM_ITERATIONS_TAG).toInt

  //  Context object containing experiment definition
  private implicit val experimentData: SigoptExperimentData =
    SigoptExperimentData(experimentLoc, benchmarkLoc, development = false)

  // RUN METHOD //
  if (experimentData.isParallel) {
    new PrintWriter("expid.txt") { write(s"${experimentData.experiment.getId}"); close() }
  } else {
    //  Must have implicit SigOptExperimentData as context object in scope
    val experimentRunner: ExperimentRunner = ExperimentRunner()
    experimentRunner.runExperiment(numIters)
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
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

}
