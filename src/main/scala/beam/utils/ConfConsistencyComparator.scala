package beam.utils
import java.io.File

import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}

import scala.collection.JavaConverters._

case class ConfConsistencyComparator(userConfFileLocation: String) extends LazyLogging {

  def parseBeamTemplateConfFile(): Unit = {
    val baseUserConf = ConfigFactory.parseFile(new File(userConfFileLocation))

    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf")).resolve()


    logger.info("###List of deprecated parameters###")
    deprecatedParametersInConfig(userConf, templateConf)

    logger.info("###List of default parameters###")
    defaultParametersInConfig(userConf, templateConf)
  }

  def deprecatedParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {

    userConf.entrySet.asScala.foreach{ entry =>
      if (!(templateConf.hasPathOrNull(entry.getKey))) {
        logger.info(entry.getKey)
      }
    }
  }

  def defaultParametersInConfig(userConf: TypesafeConfig, templateConf: TypesafeConfig): Unit = {

    templateConf.entrySet().asScala.foreach { entry =>
        if (!(userConf.hasPathOrNull(entry.getKey))) {
          logger.info("Key= " + entry.getKey + " ,Value= " + entry.getValue.render)
        }
    }
  }
  parseBeamTemplateConfFile
}
