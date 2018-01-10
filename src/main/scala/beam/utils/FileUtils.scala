package beam.utils

import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream

import beam.sim.config.BeamConfig
import org.matsim.core.config.Config
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils {

  private val logger = LoggerFactory.getLogger(getClass.getName)

  def setConfigOutputFile(beamConfig: BeamConfig, matsimConfig: Config): Unit = {
    val baseOutputDir = Paths.get(beamConfig.beam.outputs.baseOutputDirectory)
    if (!Files.exists(baseOutputDir)) baseOutputDir.toFile.mkdir()

    val outputDir = if (beamConfig.beam.outputs.addTimestampToOutputDirectory) {
      val timestamp: String = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date())
      Paths.get(beamConfig.beam.outputs.baseOutputDirectory + File.separator + beamConfig.beam.agentsim.simulationName + "_" + timestamp)
    } else {
      Paths.get(beamConfig.beam.outputs.baseOutputDirectory + File.separator + beamConfig.beam.agentsim.simulationName)
    }
    outputDir.toFile.mkdir()
    matsimConfig.controler.setOutputDirectory(outputDir.toAbsolutePath.toString)
  }

  def decompress(compressed: Array[Byte]): Option[String] =
    Try {
      val inputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))
      scala.io.Source.fromInputStream(inputStream).mkString
    }.toOption


  def using[A <: {def close() : Unit}, B](resource: A)(f: A => B): B =
    try {
      f(resource)
    } finally {
      resource.close()
    }
}
