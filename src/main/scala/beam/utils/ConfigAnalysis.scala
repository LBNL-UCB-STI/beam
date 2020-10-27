package beam.utils

import com.typesafe.config.{Config, ConfigObject, ConfigResolveOptions, ConfigValue, ConfigValueType}

import scala.collection.JavaConverters._

//Run application by providing 2 config file
// args(0) - Existing config file
// args(1) - Beam template config file
object ConfigAnalysis {

  val skipKeys = Seq(
    "akka",
    "beam-agent-scheduler-pinned-dispatcher",
    "zonal-parking-manager-pinned-dispatcher",
    "parallel-parking-manager-dispatcher",
    "ride-hail-manager-pinned-dispatcher",
    "my-custom-mailbox"
  )

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      throw new RuntimeException("Either template or beam config file not provided")
    }
    val existingConfig = BeamConfigUtils.parseFileSubstitutingInputDirectory(args(0))
    val templateConfig = BeamConfigUtils.parseFileSubstitutingInputDirectory(args(1))
    val options = ConfigResolveOptions.defaults()
    options.setAllowUnresolved(true)
    options.setUseSystemEnvironment(true)

    println("----------------Keys in config but not in beam template-----------------")
    compareExistingKeys(existingConfig.resolve(options), templateConfig)
    println("\n\n")
    println("----------------Keys in beam template but not in beam config-----------------")
    compareTemplateKeysPath(existingConfig, templateConfig)
  }

  def compareExistingKeys(config: Config, templateConfig: Config): Unit = {

    config
      .entrySet()
      .asScala
      .map(_.getKey)
      .filterNot(key => {
        skipKeys.count(key.startsWith) != 0 || templateConfig.hasPathOrNull(key)
      })
      .foreach(println)
  }

  def compareTemplateKeysPath(config: Config, templateConfig: Config): Unit = {

    templateConfig.entrySet().asScala.map(_.getKey).filterNot(config.hasPathOrNull).foreach(println)
  }
}
