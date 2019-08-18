package beam.utils

import java.io.File
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FilenameUtils._

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}

object BeamConfigUtils {

  def parseFileSubstitutingInputDirectory(fileName: String): com.typesafe.config.Config = {
    val file = Paths.get(fileName).toFile
    if (!file.exists()) throw new Exception(s"Missing config file on path $fileName")
    parseFileSubstitutingInputDirectory(file)
  }

  def parseFileSubstitutingInputDirectory(file: File): com.typesafe.config.Config = {
    ConfigFactory
      .parseFile(file)
      .withFallback(
        ConfigFactory.parseMap(Map("beam.inputDirectory" -> file.getAbsoluteFile.getParent).asJava)
      )
  }

  class ConfigPathsCollector(readFile: String => Array[String] = readFileLines) {

    private def getIncludedPaths(path: String): Array[String] = {
      val basePath = getFullPath(path)
      def correctRelativePath(relativePath: String): String = normalize(Paths.get(basePath, relativePath).toString)

      val lines = readFile(path)
      val includedPaths = lines
        .map(line => line.split('"').map(_.trim))
        .collect { case Array("include", path, _*) => path }
        .map(correctRelativePath)

      includedPaths
    }

    private def getIncludedPathsRecursively(
      path: String,
      processedPaths: immutable.HashSet[String] = immutable.HashSet.empty[String]
    ): immutable.HashSet[String] = {
      val includedPaths = getIncludedPaths(path)
      val unprocessedPaths = includedPaths.collect {
        case path if !processedPaths.contains(path) => path
      }

      unprocessedPaths.foldLeft(processedPaths + path) {
        case (accumulator, unprocessed) => getIncludedPathsRecursively(unprocessed, accumulator)
      }
    }

    def getFileNameToPath(configFileLocation: String): Map[String, String] = {
      val confFileNames = mutable.Map.empty[String, Int]
      val confFileLocationNormalized = normalize(configFileLocation)
      val allIncludedPaths = getIncludedPathsRecursively(confFileLocationNormalized)

      val confNameToPaths = (allIncludedPaths - confFileLocationNormalized)
        .map(_.toString)
        .foldLeft(Map("beam.conf" -> confFileLocationNormalized)) {
          case (fileNameToPath, confFilePath) =>
            val confFileName = getName(confFilePath)
            val newConfFileName = confFileNames.get(confFileName) match {
              case None =>
                confFileNames(confFileName) = 1
                confFileName
              case Some(cnt) =>
                confFileNames(confFileName) = cnt + 1
                val fExtension = getExtension(confFileName)
                removeExtension(confFileName) + s"_$cnt" + EXTENSION_SEPARATOR_STR + fExtension
            }

            fileNameToPath + (newConfFileName -> confFilePath)
        }

      confNameToPaths
    }
  }

  def getFileNameToPath(configFileLocation: String): Map[String, String] = {
    val collector = new ConfigPathsCollector()
    collector.getFileNameToPath(configFileLocation)
  }

  private def readFileLines(filePath: String): Array[String] = {
    val source = scala.io.Source.fromFile(filePath)
    val lines = source.getLines.toArray
    source.close()
    lines
  }

}
