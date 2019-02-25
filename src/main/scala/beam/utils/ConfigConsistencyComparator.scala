package beam.utils
import java.io.File

import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, Config => TypesafeConfig}

import scala.collection.JavaConverters._

case class ConfigConsistencyComparator(userConfFileLocation: String) extends LazyLogging {

  def parseBeamTemplateConfFile(): Unit = {
    val baseUserConf = ConfigFactory.parseFile(new File(userConfFileLocation))

    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf")).resolve()

    var logString = "\n\n*************************************************************************************************************************\n" +
    "** Config File Consistency Check\n" +
    "** Testing your config file against what BEAM is expecting.\n" +
    "**\n"
    val deprecatedString = deprecatedParametersInConfig(userConf, templateConf)
    if (!deprecatedString.equals("**\n")) {
      logString += "** Found the following deprecated parameters, you can safely remove them from your config file:\n"
      logString += deprecatedString
      logString += "**\n"
    }
    val defaultString = defaultParametersInConfig(userConf, templateConf)
    if (!defaultString.equals("**\n")) {
      logString += "** The following parameters were missing from your config file, this is ok, but FYI these default values will be assigned:\n"
      logString += defaultString
    }

    if (deprecatedString.equals("**\n") && defaultString.equals("**\n")) {
      logString += "** All good, your config file is fully consistent!\n"
      logString += "**\n"
    }
    logString += "*************************************************************************************************************************\n"

    logger.info(logString)
  }

  def deprecatedParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): String = {

    var logString = "**\n"
    userConf.entrySet.asScala.foreach { entry =>
      if (!(templateConf.hasPathOrNull(entry.getKey))) {
        logString += "**\t" + entry.getKey + "\n"
      }
    }
    logString
  }

  def defaultParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): String = {

    var logString = "**\n"
    val theparams = templateConf
      .entrySet()
      .asScala
      .filter(entry => !userConf.hasPathOrNull(entry.getKey))
      .map { entry =>
        val paramValue = entry.getValue.unwrapped.toString
        val value = paramValue.substring(paramValue.lastIndexOf('|') + 1).trim
        (entry.getKey -> value)
      }
      .toMap
    theparams.keys.toList.sorted.foreach { entryKey =>
      val value = theparams(entryKey)
      logString += "**\t" + entryKey + " = " + value + "\n"
    }
    logString
  }
  parseBeamTemplateConfFile
}
