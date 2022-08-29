package beam.calibration

import beam.calibration.utils.SigOptApiToken
import beam.calibration.CalibrationArguments._
import beam.experiment.ExperimentApp
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang.SystemUtils

/**
  *  To run on t2.large w/ 16g (Max) RAM on AWS via Gradle build script, do the following:
  *
  *  <code>
  * gradle :deploy -PrunName=test-calibration -PinstanceType=t2.large -PmaxRAM=32g -PdeployMode=execute  -PexecuteClass=beam.calibration.RunCalibration -PexecuteArgs="['--experiments', 'test/input/sf-light/sf-light-calibration/experiment.yml', '--benchmark', '--experiment_id', '12034', 'test/input/sf-light/sf-light-calibration/benchmarkTest.csv','--num_iters', '100']"
  *  </code>
  *
  * For now, to view logging in console, SSH into created instance, and run:
  *
  *  <code> tail -f /var/log/cloud-init-output.log </code>
  */
object RunCalibration extends ExperimentApp with LazyLogging {

  // requirement:
  /*
    --runType local|remote
    - we need local run
    - we need to deploy runs
   */
  // - we need to be able to create new experiment id - [DONE]
  // - we need to pass existing experiment id to local and remote runs we start - [DONE]
  // - the for sigopt should be able to run locally (avoid each dev to have sigopt dev api token) - [LATER]
  // - provide a objective function with is a combination of modes and counts

  // Store CLI inputs as private members
  private val experimentLoc: String = argsMap(EXPERIMENTS_TAG)
  private val benchmarkLoc: String = argsMap(BENCHMARK_EXPERIMENTS_TAG)
  private val numIters: Int = argsMap(NUM_ITERATIONS_TAG).toInt
  private val experimentId: String = argsMap(EXPERIMENT_ID_TAG)
  private val runType: String = argsMap(RUN_TYPE)
  private val sigoptApiToken: String = argsMap(SIGOPT_API_TOKEN_TAG)

  // @Asif: TODO hide the checking into SigOptApiToken.getClientAPIToken
  try {
    if (sigoptApiToken != null) {
      logger.info("The client token is set from the program arguments")
      Sigopt.clientToken = sigoptApiToken
    } else {
      Sigopt.clientToken = SigOptApiToken.getClientAPIToken
    }
  } catch {
    case ex: APIConnectionError =>
      logger.info(ex.getMessage)
  }

  // Aux Methods //
  override def parseArgs(args: Array[String]): Map[String, String] = {
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
          if (trimmedExpId == "None") {
            (EXPERIMENT_ID_TAG, NEW_EXPERIMENT_FLAG)
          } else {
            (EXPERIMENT_ID_TAG, trimmedExpId)
          }
        case Array("--run_type", runType: String) if runType.trim.nonEmpty =>
          (RUN_TYPE, runType)
        case Array("--sigopt_api_token", sigoptApiToken: String) if sigoptApiToken.trim.nonEmpty =>
          (SIGOPT_API_TOKEN_TAG, sigoptApiToken)
        case arg =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  //  Context object containing experiment definition
  private implicit val experimentData: SigoptExperimentData =
    SigoptExperimentData(experimentDef, benchmarkLoc, experimentId)

  if (runType == RUN_TYPE_LOCAL) {
    val experimentRunner: ExperimentRunner = ExperimentRunner()
    experimentRunner.runExperiment(numIters)
  } else if (runType == RUN_TYPE_REMOTE) {
    logger.info("Triggering the remote deployment...")
    import sys.process._
    val gradlewEnding = if (SystemUtils.IS_OS_WINDOWS) {
      ".bat"
    } else {
      ".sh"
    }

    (1 to experimentData.numWorkers).foreach { _ =>
      val execString: String =
        s"""./gradlew$gradlewEnding :deploy
            -PrunName=${experimentData.experimentDef.header.title}
            -PbeamBranch=${experimentData.experimentDef.header.deployParams.get("beamBranch")}
           -PbeamCommit=${experimentData.experimentDef.header.deployParams.get("beamCommit")}
           -PdeployMode=${experimentData.experimentDef.header.deployParams.get("deployMode")}
           -PexecuteClass=${experimentData.experimentDef.header.deployParams.get("executeClass")}
           -PbeamBatch=${experimentData.experimentDef.header.deployParams.get("beamBatch")}
           -PshutdownWait=${experimentData.experimentDef.header.deployParams.get("shutdownWait")}
           -PshutdownBehavior=${experimentData.experimentDef.header.deployParams.get("shutdownBehavior")}
           -Ps3Backup=${experimentData.experimentDef.header.deployParams.get("s3Backup")}
           -PmaxRAM=${experimentData.experimentDef.header.deployParams.get("maxRAM")}
           -Pregion=${experimentData.experimentDef.header.deployParams.get("region")}
           -PinstanceType=${experimentData.experimentDef.header.deployParams.get("instanceType")}
            -PexecuteArgs="[
           '--experiments', '$experimentLoc',
           '--experiment_id', '${experimentData.experiment.getId}',
           '--benchmark','$benchmarkLoc',
           '--num_iters', '$numIters',
           '--run_type', 'local',
           '--sigopt_api_token', '$sigoptApiToken']"""".stripMargin
      logger.debug(execString)
      execString.!
    }
  } else {
    logger.error("{} unknown", RUN_TYPE)
  }

}
