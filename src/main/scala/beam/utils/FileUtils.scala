package beam.utils

import java.io.{BufferedInputStream, ByteArrayInputStream, File, FileInputStream}
import java.net.URL
import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.stream
import java.util.zip.GZIPInputStream

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.FileUtils.{copyURLToFile, getTempDirectoryPath}
import org.apache.commons.io.FilenameUtils.getName
import org.matsim.core.config.Config
import org.matsim.core.utils.io.IOUtils
import scala.language.reflectiveCalls
import scala.util.Try

/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils extends LazyLogging {

  val runStartTime: String = getDateString

  def setConfigOutputFile(beamConfig: BeamConfig, matsimConfig: Config): Unit = {
    val baseOutputDir = Paths.get(beamConfig.beam.outputs.baseOutputDirectory)
    if (!Files.exists(baseOutputDir)) baseOutputDir.toFile.mkdir()

    val optionalSuffix: String = getOptionalOutputPathSuffix(
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )

    val outputDir = Paths
      .get(
        beamConfig.beam.outputs.baseOutputDirectory + File.separator + beamConfig.beam.agentsim.simulationName + optionalSuffix
      )
      .toFile
    outputDir.mkdir()
    logger.debug(s"Beam output directory is: ${outputDir.getAbsolutePath}")
    matsimConfig.controler.setOutputDirectory(outputDir.getAbsolutePath)
  }

  def getConfigOutputFile(
    outputDirectoryBasePath: String,
    simulationName: String,
    addTimestampToOutputDirectory: Boolean
  ): String = {
    val baseOutputDir = Paths.get(outputDirectoryBasePath)
    if (!Files.exists(baseOutputDir)) baseOutputDir.toFile.mkdir()

    val optionalSuffix: String = getOptionalOutputPathSuffix(addTimestampToOutputDirectory)
    val outputDir = Paths
      .get(outputDirectoryBasePath + File.separator + simulationName + "_" + optionalSuffix)
      .toFile
    logger.debug(s"Beam output directory is: ${outputDir.getAbsolutePath}")
    outputDir.mkdir()
    outputDir.getAbsolutePath
  }

  def getOptionalOutputPathSuffix(addTimestampToOutputDirectory: Boolean): String = {
    if (addTimestampToOutputDirectory) s"_$runStartTime"
    else ""
  }

  private def getDateString: String =
    new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date())

  def createDirectoryIfNotExists(path: String): Boolean = {
    val dir = new File(path).getAbsoluteFile
    if (!dir.exists() && !dir.isDirectory) {
      dir.mkdirs()
    } else {
      false
    }
  }

  def using[A <: { def close(): Unit }, B](resource: A)(f: A => B): B =
    try {
      f(resource)
    } finally {
      resource.close()
    }

  def safeLines(fileLoc: String): stream.Stream[String] = {
    using(readerFromFile(fileLoc))(_.lines)
  }

  def readerFromFile(filePath: String): java.io.BufferedReader = {
    IOUtils.getBufferedReader(filePath)
  }

  def downloadFile(source: String): Unit = {
    downloadFile(source, Paths.get(getTempDirectoryPath, getName(source)).toString)
  }

  def downloadFile(source: String, target: String): Unit = {
    assert(source != null)
    assert(target != null)
    copyURLToFile(new URL(source), Paths.get(target).toFile)
  }

  def getHash(concatParams: Any*): Int = {
    val concatString = concatParams.foldLeft("")(_ + _)
    concatString.hashCode
  }
}
