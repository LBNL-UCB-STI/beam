package beam.calibration

import java.io.File
import java.nio.file.{Files, Path}

import beam.experiment.ExperimentGenerator
import beam.sim.BeamHelper
import com.sigopt.Sigopt
import com.sigopt.model.Experiment

object RunCalibration extends App with BeamHelper {

  private val EXPERIMENTS_TAG = "experiments"
  private val CLIENT_ID_TAG = "client_token"

  val argsMap = parseArgs(args)
  Sigopt.clientToken = argsMap(CLIENT_ID_TAG)

  val experimentPath: Path = new File(argsMap(EXPERIMENTS_TAG)).toPath.toAbsolutePath

  if (!Files.exists(experimentPath)) {
    throw new IllegalArgumentException(s"$EXPERIMENTS_TAG file is missing: $experimentPath")
  }

  val experimentDef = ExperimentGenerator.loadExperimentDefs(experimentPath.toFile)

  private val experimentData: SigoptExperimentData = SigoptExperimentData(experimentDef, experimentPath.toFile)
  private val experiment: Experiment = BeamSigoptTuner.createOrFetchExperiment(experimentData)

  (1 to 20).foreach { i =>
    val suggestion = experiment.suggestions.create.call
    val newRun = runBeamWithConfig(BeamSigoptTuner.createConfigBasedOnAssignments(suggestion.getAssignments, experimentData, suggestion.getId))


  }


  // METHODS //

  def parseArgs(args: Array[String]) = {
    args.sliding(2, 1).toList.collect {
      case Array("--experiments", filePath: String) if filePath.trim.nonEmpty => (EXPERIMENTS_TAG, filePath)
      case Array("--client_token", clientId: String) if clientId.trim().nonEmpty =>
        (CLIENT_ID_TAG, clientId)
      case arg@_ =>
        throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }


}
