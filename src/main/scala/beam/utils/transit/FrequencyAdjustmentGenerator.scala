package beam.utils.transit

import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import beam.utils.transit.FrequencyAdjustmentUtils.generateFrequencyAdjustmentCsvFile
import com.typesafe.config.Config

/**
  * Run directly from CLI with, for example:
  * {{{
  *   ./gradlew execute -PmainClass=beam.utils.transit.FrequencyAdjustmentGenerator -PappArgs=\
  *   "['--config', 'test/input/beamville/beam.conf', '--frequencyAdjustmentFile', 'test/input/beamville/r5/FrequencyAdjustment.csv']"
  * }}}
  */
object FrequencyAdjustmentGenerator extends App {

  val ConfigFile = "--config"
  val FrequencyAdjustmentFile = "--frequencyAdjustmentFile"

  def parseArgs(args: Array[String]): Map[String, String] = {
    args
      .sliding(2, 2)
      .toList
      .collect {
        case Array(ConfigFile, filePath: String) if filePath.trim.nonEmpty =>
          (ConfigFile, filePath)
        case Array(FrequencyAdjustmentFile, filePath: String) if filePath.trim.nonEmpty =>
          (FrequencyAdjustmentFile, filePath)
        case arg =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  val argsMap = parseArgs(args)

  val config: Config = BeamConfigUtils
    .parseFileSubstitutingInputDirectory(argsMap(ConfigFile))
    .resolve()
  val beamConfig: BeamConfig = BeamConfig(config)

  private lazy val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
  networkCoordinator.loadNetwork()

  generateFrequencyAdjustmentCsvFile(
    networkCoordinator.transportNetwork.transitLayer,
    argsMap(FrequencyAdjustmentFile)
  )
}
