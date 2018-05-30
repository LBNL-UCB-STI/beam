package beam.calibration

import beam.sim.BeamHelper
import com.sigopt.Sigopt

object RunCalibration extends App with BeamHelper {

  private val EXPERIMENTS_TAG = "experiments"
  private val CLIENT_ID_TAG = "client_token"

  val argsMap = parseArgs(args)

  Sigopt.clientToken = argsMap(CLIENT_ID_TAG)

  private val experimentLoc = argsMap(EXPERIMENTS_TAG)

  private implicit val experimentData: SigoptExperimentData = SigoptExperimentData(experimentLoc)
  
  private val experimentRunner: ExperimentRunner = ExperimentRunner()
  
  (1 to 20).foreach { i =>
    val newRunConfig = experimentRunner
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
