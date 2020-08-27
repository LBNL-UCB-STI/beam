package beam.sim.config

import java.io.{File, PrintWriter}

import com.typesafe.config.{ConfigRenderOptions, ConfigResolveOptions, ConfigFactory, Config => TypesafeConfig}
import scala.collection.JavaConverters._

object ConfigHelper {

  private val skipConfigParams = List(
    "matsim.modules.vehicles.vehiclesFile",
    "matsim.modules.transit.vehiclesFile",
    "matsim.modules.counts.inputCountsFile",
    "matsim.modules.strategy.planSelectorForRemoval"
  )

  /**
    * This method merges all configuration parameters into a single file including parameters from
    * 'include' statements. Two full config files are written out: One without comments and one with
    * comments in JSON format.
    * @param config the input config file
    * @param outputDirectory output folder where full configs will be generated
    */
  def writeFullConfigs(config: TypesafeConfig, outputDirectory: String): Unit = {

    val configResolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)
    var templateConf = ConfigFactory.parseResources("beam-template.conf")
    skipConfigParams.foreach(configKey => {
      templateConf = templateConf.withoutPath(configKey)
    })
    val fullConfig = config.resolve().withFallback(templateConf.resolve(configResolveOptions)).resolve()

    val defaultConfig = fullConfig
      .entrySet()
      .asScala
      .collect {
        case entry if shouldAddKey(entry.getValue.unwrapped) =>
          val unwrapped = entry.getValue.unwrapped()
          val paramValue = unwrapped.toString
          if (paramValue.contains("|")) {
            entry.getKey -> actualValue(paramValue)
          } else {
            entry.getKey -> unwrapped
          }
      }
      .toMap
      .asJava
    val defaultValues = ConfigFactory.parseMap(defaultConfig).resolve()
    val configConciseWithoutJson =
      defaultValues.root().render(ConfigRenderOptions.concise().setFormatted(true).setJson(false))
    writeStringToFile(configConciseWithoutJson, new File(outputDirectory, "fullBeamConfig.conf"))
    writeStringToFile(defaultValues.root().render(), new File(outputDirectory, "fullBeamConfigJson.conf"))
  }

  private def actualValue(paramValue: String): Any = {
    val value = paramValue.substring(paramValue.lastIndexOf('|') + 1).trim
    if (paramValue.contains("int")) {
      return value.toInt
    }
    if (paramValue.contains("double")) {
      if ("Double.PositiveInfinity" == value) {
        return Double.PositiveInfinity
      }
      if ("Double.NegativeInfinity" == value) {
        return Double.NegativeInfinity
      }
      return value.toDouble
    }
    if (paramValue.contains("boolean")) {
      return value.toBoolean
    }
    value
  }

  private def shouldAddKey(value: AnyRef): Boolean = {
    if ("int?" == value.toString || "double?" == value.toString || "[double]" == value.toString) {
      return false
    }
    true
  }

  private def writeStringToFile(text: String, output: File): Unit = {
    val fileWriter = new PrintWriter(output)
    fileWriter.write(text)
    fileWriter.close
  }
}
