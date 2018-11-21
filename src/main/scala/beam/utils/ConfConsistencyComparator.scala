package beam.utils
import java.io.File

import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}

import scala.collection.convert.DecorateAsScala

case class ConfConsistencyComparator(userConfFileLocation: String) extends LazyLogging with DecorateAsScala {

  def parseBeamTemplateConfFile(): Unit = {
    val baseUserConf = ConfigFactory.parseFile(new File(userConfFileLocation))

    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf"))

    logger.info("###List of deprecated parameters###")
    deprecatedParametersInConfig(userConf, templateConf)

    logger.info("###List of default parameters###")
    defaultParametersInConfig(userConf, templateConf)
  }

  def deprecatedParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {
    userConf.entrySet().asScala.foldLeft() {
      case (acc, entry) =>
        val paramKey = entry.getKey
        if (!(templateConf.hasPathOrNull(paramKey))) {
          logger.info(paramKey)
        }
    }
  }

  def defaultParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {

    templateConf.entrySet().asScala.foldLeft() {
      case (acc, entry) =>
        val paramKey = entry.getKey
        if (!(userConf.hasPathOrNull(paramKey))) {
          logger.info("Key= " + paramKey + " ,Value= " + entry.getValue.render)
        }
    }
  }
  parseBeamTemplateConfFile
}
