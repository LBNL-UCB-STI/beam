package beam.utils

import java.io.File

import scala.collection.JavaConverters._

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigResolveOptions, ConfigValue, Config => TypesafeConfig}
import com.typesafe.scalalogging.LazyLogging

object ConfigConsistencyComparator extends LazyLogging {
  private val eol = System.lineSeparator()
  private val borderLeft = "**  "
  private val topicBorderLeft = "** "
  private val sessionSeparator = "*" * 122
  private val top = {
    eol + sessionSeparator +
    buildTopicTile("Config File Consistency Check") +
    buildTopicTile("Testing your config file against what BEAM is expecting.")
  }
  private val bottom = sessionSeparator + eol
  private val consistentFileMessage = buildTopicTile("All good, your config file is fully consistent!")

  def parseBeamTemplateConfFile(rootFolder: String, userConfFileLocation: String): Unit = {
    val configResolver = ConfigResolveOptions
      .defaults()
      .setAllowUnresolved(true)

    val baseUserConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(new File(userConfFileLocation))
    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf).resolve(configResolver)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf")).resolve()

    val logStringBuilder = new StringBuilder(top)

    val deprecatedKeys = findDeprecatedKeys(userConf, templateConf)
    if (deprecatedKeys.nonEmpty) {
      val title = "Found the following deprecated parameters, you can safely remove them from your config file:"
      logStringBuilder.append(buildTopicWithKeys(title, deprecatedKeys))
    }

    val paramsWithDefaultValues = findParamsWithDefaultValues(userConf, templateConf)
    if (paramsWithDefaultValues.nonEmpty) {
      val title =
        "The following parameters were missing from your config file, this is ok, but FYI these default values will be assigned:"
      logStringBuilder.append(buildTopicWithKeysAndValues(title, paramsWithDefaultValues))
    }

    if (deprecatedKeys.isEmpty && paramsWithDefaultValues.isEmpty) {
      logStringBuilder.append(consistentFileMessage)
    }

    val notFoundFiles = findNotFoundFiles(rootFolder, userConf)
    if (notFoundFiles.nonEmpty) {
      val title = "The following files were not found:"
      logStringBuilder.append(buildTopicWithKeysAndValues(title, notFoundFiles))
    }

    logStringBuilder.append(bottom)

    logger.info(logStringBuilder.toString)

    if (notFoundFiles.nonEmpty) {
      throw new IllegalArgumentException("There are not found files.")
    }
  }

  def findDeprecatedKeys(userConf: TypesafeConfig, templateConf: TypesafeConfig): Seq[String] = {
    userConf
      .entrySet()
      .asScala
      .map(_.getKey)
      .filterNot(templateConf.hasPathOrNull)
      .toSeq
  }

  def findParamsWithDefaultValues(userConf: TypesafeConfig, templateConf: TypesafeConfig): Seq[(String, String)] = {
    templateConf
      .entrySet()
      .asScala
      .map { entry =>
        val paramValue = entry.getValue.unwrapped.toString
        val value = paramValue.substring(paramValue.lastIndexOf('|') + 1).trim
        entry.getKey -> value
      }
      .filterNot { case (key, _) => userConf.hasPathOrNull(key) }
      .toSeq
  }

  private def buildTopicWithKeysAndValues(title: String, keysAndValues: Seq[(String, String)]): String = {
    buildTopicTile(title) + buildStringFromKeysAndValues(keysAndValues)
  }

  def buildTopicWithKeys(title: String, keys: Seq[String]): String = {
    buildTopicTile(title) + buildStringFromKeys(keys)
  }

  def buildTopicTile(title: String): String = {
    s"""$borderLeft
       |$topicBorderLeft$title
       |""".stripMargin
  }

  def findNotFoundFiles(rootFolder: String, userConf: TypesafeConfig): Seq[(String, String)] = {

    def resolve(key: String, value: ConfigValue): String = {
      try {
        value.unwrapped().toString
      } catch {
        case _: ConfigException.NotResolved => value.render()
      }
    }

    ConfigResolveOptions.defaults()
    val filePaths = userConf
      .entrySet()
      .asScala
      .map(entry => (entry.getKey, resolve(entry.getKey, entry.getValue)))
      .filter { case (key, value) => key.toLowerCase.endsWith("filepath") }
      .toSeq

    filePaths.filter {
      case (key, value) =>
        val fullPath = value
          .replace("${beam.inputDirectory}", rootFolder)
          .replaceAll("\"", "")
        val canRead = new File(fullPath).canRead
        !canRead
    }
  }

  private def buildStringFromKeysAndValues(pairs: Seq[(String, String)]): String = {
    pairs
      .sortBy { case (key, _) => key }
      .map { case (key, value) => s"$borderLeft$key = [$value]" }
      .mkString(borderLeft + eol, eol, eol)
  }

  private def buildStringFromKeys(keys: Seq[String]): String = {
    keys.sorted
      .map(key => s"$borderLeft$key")
      .mkString(borderLeft + eol, eol, eol)
  }

}
