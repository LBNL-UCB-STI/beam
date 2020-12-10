package beam.utils

import java.io.File

import com.typesafe.config.{Config, ConfigResolveOptions}
import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._

/*
Run application by providing 2 config file
args(0) - directory for config files
args(1) - Beam template config file
args(2) - Optional param for comparing existing config with template
if args(2) is 0 => give existing config files keys only(Default)
if args(2) is 1 => give template keys only
if args(2) is 2 => give keys for both existing config and beam template
./gradlew :execute -PmainClass=beam.utils.ConfigAnalysis -PappArgs="['test/input/beamville','src/main/resources/beam-template.conf']"
or
./gradlew :execute -PmainClass=beam.utils.ConfigAnalysis -PappArgs="['test/input/beamville','src/main/resources/beam-template.conf', '2']"
 */
object ConfigAnalysis extends App with StrictLogging {

  val skipKeys = Seq(
    "akka",
    "beam-agent-scheduler-pinned-dispatcher",
    "zonal-parking-manager-pinned-dispatcher",
    "parallel-parking-manager-dispatcher",
    "ride-hail-manager-pinned-dispatcher",
    "my-custom-mailbox"
  )

  var mode = 1
  if (args.length == 3) {
    mode = args(2).toInt
  } else if (args.length < 2) {
    throw new RuntimeException("Either template or beam config file not provided")
  }
  val options = ConfigResolveOptions.defaults()
  options.setAllowUnresolved(true)
  options.setUseSystemEnvironment(true)

  val templateConfig = BeamConfigUtils.parseFileSubstitutingInputDirectory(args(1))

  val configFiles = listFiles(new File(args(0))).map(_.getPath).filter(_.endsWith(".conf"))
  if (configFiles.isEmpty) {
    logger.info(s"No config file found in provided directory ${args(0)}!!!")
  } else {
    configFiles.foreach(path => {

      val existingConfig = BeamConfigUtils.parseFileSubstitutingInputDirectory(path)
      logger.info("######################################################################################")
      logger.info(s"Start analysis on $path")
      if (mode == 1 || mode == 3) {
        logger.info(s"----------------Keys in config $path but not in beam template-----------------")
        compareExistingKeys(existingConfig.resolve(options), templateConfig)
      }

      if (mode == 2 || mode == 3) {
        logger.info(s"----------------Keys in beam template but not in beam config ${path}-----------------")
        compareTemplateKeysPath(existingConfig, templateConfig)
      }
      logger.info(s"End analysis on $path")
      logger.info("######################################################################################")

    })
  }

  def compareExistingKeys(config: Config, templateConfig: Config): Unit = {

    config
      .entrySet()
      .asScala
      .map(_.getKey)
      .filterNot(key => {
        skipKeys.count(key.startsWith) != 0 || templateConfig.hasPathOrNull(key)
      })
      .foreach(x => logger.info(x))
  }

  def compareTemplateKeysPath(config: Config, templateConfig: Config): Unit = {

    templateConfig.entrySet().asScala.map(_.getKey).filterNot(config.hasPathOrNull).foreach(x => logger.info(x))
  }

  final def listFiles(base: File): Seq[File] = {
    val files = base.listFiles
    val result = files.filter(_.isFile)
    result ++
    files
      .filter(_.isDirectory)
      .flatMap(listFiles)
  }
}
