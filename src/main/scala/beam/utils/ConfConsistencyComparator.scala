package beam.utils
import java.io.File

import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, Config => TypesafeConfig}

import scala.collection.JavaConverters._

case class ConfConsistencyComparator(userConfFileLocation: String) extends LazyLogging {

  def parseBeamTemplateConfFile(): Unit = {
    val baseUserConf = ConfigFactory.parseFile(new File(userConfFileLocation))

    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf")).resolve()

    deprecatedParametersInConfig(userConf, templateConf)

    defaultParametersInConfig(userConf, templateConf)
  }

  def deprecatedParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {

    var logString = "###List of deprecated parameters###"
    userConf.entrySet.asScala.foreach { entry =>
      if (!(templateConf.hasPathOrNull(entry.getKey))) {
        logString += "\n\t" + entry.getKey
      }
    }
    logger.info(logString)
  }

  def defaultParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {

    var logString = "###List of default parameters###"
    templateConf.entrySet().asScala.foreach { entry =>
      val paramValue = entry.getValue.unwrapped.toString
      val value = paramValue.substring(paramValue.lastIndexOf('|')+1).trim
      if (!(userConf.hasPathOrNull(entry.getKey))) {
        logString += "\n\t" + entry.getKey + " = " + value
      }
    }
    logger.info(logString)
  }
  parseBeamTemplateConfFile
}
