package beam.calibration

import java.io.PrintWriter

import beam.sim.BeamHelper
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import org.apache.commons.lang.SystemUtils

/**
  To run on t2.large w/ 16g (Max) RAM on AWS via Gradle build script, do the following:
  *
  <code>
 gradle :deploy -PrunName=test-calibration -PinstanceType=t2.large -PmaxRAM=32g -PdeployMode=execute  -PexecuteClass=beam.calibration.RunCalibration -PexecuteArgs="['--experiments', 'test/input/sf-light/sf-light-calibration/experiment.yml', '--benchmark', '--experiment_id', '12034', 'test/input/sf-light/sf-light-calibration/benchmarkTest.csv','--num_iters', '100']"
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
  private val EXPERIMENT_ID_TAG = "experiment_id"
  private val NEW_EXPERIMENT_FLAG = "00000"

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
  private val experimentId: String = argsMap(EXPERIMENT_ID_TAG)

  //  Context object containing experiment definition
  private implicit val experimentData: SigoptExperimentData =
    SigoptExperimentData(experimentLoc, benchmarkLoc, experimentId, development = false)

  val iterPerNode = Math.ceil(numIters / experimentData.numWorkers)

  if (experimentData.isParallel && experimentData.isMaster) {
    import sys.process._
    val gradlewEnding = if(SystemUtils.IS_OS_WINDOWS){".bat"}else{".sh"}

    (1 to experimentData.numWorkers).foreach({ _ =>
      val execString:String =s"./gradlew$gradlewEnding :deploy -PrunName=\'${experimentData.experimentDef.header.title}\' -PinstanceType=\'t2.large\' -PmaxRAM=\'128g\' -PdeployMode=\'execute\'  -PexecuteClass=\'beam.calibration.RunCalibration\' -PexecuteArgs=\'['--experiments', '$experimentLoc',  '--experiment_id', '${experimentData.experiment.getId}', '--benchmark','$benchmarkLoc','--num_iters', '$iterPerNode']\'"
      println(execString)
      execString.!
    })
  }

  // Runs calibration
  val experimentRunner: ExperimentRunner = ExperimentRunner()
  experimentRunner.runExperiment(numIters)

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
          if (trimmedExpId == "None") { (EXPERIMENT_ID_TAG, trimmedExpId) } else {
            (EXPERIMENT_ID_TAG, NEW_EXPERIMENT_FLAG)
          }
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

}
