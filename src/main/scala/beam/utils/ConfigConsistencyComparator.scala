package beam.utils

import java.io.File

import scala.collection.JavaConverters._
import com.typesafe.config.{ConfigException, ConfigFactory, ConfigResolveOptions, ConfigValue, Config => TypesafeConfig}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.io.Source
import scala.util.Try

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

  private val ignorePaths: Set[String] = Set("beam.physsim.inputNetworkFilePath")

  private var consistencyMessage: Option[String] = None

  def getMessage: Option[String] = {
    consistencyMessage
  }

  def parseBeamTemplateConfFile(userConfFileLocation: String): Unit = {
    val logStringBuilder = new java.lang.StringBuilder(top)
    val configResolver = ConfigResolveOptions
      .defaults()
      .setAllowUnresolved(true)

    val baseUserConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(new File(userConfFileLocation))
    val userBeamConf = baseUserConf.withOnlyPath("beam")
    val userMatsimConf = baseUserConf.withOnlyPath("matsim")
    val userConf = userBeamConf.withFallback(userMatsimConf).resolve(configResolver)
    val templateConf = ConfigFactory.parseFile(new File("src/main/resources/beam-template.conf")).resolve()

    checkMapFilesDirectoriesConsistency(userConf)

    val duplicateKeys = findDuplicateKeys(userConfFileLocation)
    if (duplicateKeys.nonEmpty) {
      val title = "Found the following duplicate config keys from your config file:"
      logStringBuilder.append(buildTopicWithKeys(title, duplicateKeys))
    }

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

    val notFoundFiles = findNotFoundFiles(userConf)
    if (notFoundFiles.nonEmpty) {
      val title = "The following files were not found:"
      logStringBuilder.append(buildTopicWithKeysAndValues(title, notFoundFiles))
    }

    logStringBuilder.append(bottom)

    if (notFoundFiles.nonEmpty) {
      throw new IllegalArgumentException(
        s"The following files were not found: ${buildTopicWithKeysAndValues("", notFoundFiles)}"
      )
    }
    consistencyMessage = Some(logStringBuilder.toString)
  }

  //This method filter duplicate only for non nested keys
  def findDuplicateKeys(userConfFileLocation: String): Seq[String] = {
    val source = Source.fromFile(userConfFileLocation)
    try {
      val lines = Try(source.getLines().toList).getOrElse(List())
      val bracketStack = mutable.Stack[String]()
      val configKey = mutable.Map[String, Int]().withDefaultValue(0)
      val withoutCommentConfigLines = lines.withFilter(!_.trim.startsWith("#"))
      for (line <- withoutCommentConfigLines) {
        if (line.contains("{") && !line.contains("${")) {
          bracketStack.push("{")
        } else if (line.contains("}") && !line.contains("${")) {
          bracketStack.pop()
        } else if (bracketStack.isEmpty && line.contains("=")) {
          val keyedValue = line.split("=")
          configKey.update(keyedValue(0).trim, configKey(keyedValue(0).trim) + 1)
        }
      }
      configKey.retain((_, value) => value > 1).keys.toSeq
    } finally {
      source.close()
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

  private def buildTopicTile(title: String): String = {
    s"""$borderLeft
       |$topicBorderLeft$title
       |""".stripMargin
  }

  def findNotFoundFiles(userConf: TypesafeConfig): Seq[(String, String)] = {

    def resolve(value: ConfigValue): String = {
      try {
        value.unwrapped().toString
      } catch {
        case _: ConfigException.NotResolved => value.render()
      }
    }

    ConfigResolveOptions.defaults()
    userConf
      .entrySet()
      .asScala
      .map(entry => (entry.getKey, resolve(entry.getValue)))
      .filter { case (key, value) =>
        val shouldCheck = !ignorePaths.contains(key)
        shouldCheck && key.toLowerCase.endsWith("filepath") && value.nonEmpty && !new File(value).isFile
      }
      .toSeq
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

  def checkMapFilesDirectoriesConsistency(userConf: TypesafeConfig): Unit = {
    val r5config = userConf.getConfig("beam.routing.r5")
    val r5directory = r5config.getString("directory")
    val osmFile = r5config.getString("osmFile")
    if (!osmFile.contains(r5directory)) {
      throw new IllegalArgumentException(
        s"It is expected that beam.routing.r5.osmFile points to the file inside beam.routing.r5.directory " +
        s"[$r5directory]. Instead it points to: [$osmFile]"
      )
    }
    val osmMapdbFile = r5config.getString("osmMapdbFile")
    if (!osmMapdbFile.contains(r5directory)) {
      throw new IllegalArgumentException(
        s"It is expected that beam.routing.r5.osmMapdbFile points to the file inside beam.routing.r5.directory " +
        s"[$r5directory]. Instead it points to: [$osmMapdbFile]"
      )
    }
    val inputNetworkFilePath = userConf.getString("beam.physsim.inputNetworkFilePath")
    if (!inputNetworkFilePath.contains(r5directory)) {
      throw new IllegalArgumentException(
        s"It is expected that beam.physsim.inputNetworkFilePath points to the file inside beam.routing.r5.directory " +
        s"[$r5directory]. Instead it points to: [$inputNetworkFilePath]"
      )
    }
    val matsimInputNetworkFile = userConf.getString("matsim.modules.network.inputNetworkFile")
    if (!matsimInputNetworkFile.contains(r5directory)) {
      throw new IllegalArgumentException(
        s"It is expected that matsim.modules.network.inputNetworkFilePath points to the file inside beam.routing.r5.directory " +
        s"[$r5directory]. Instead it points to: [$matsimInputNetworkFile]"
      )
    }
  }
}
