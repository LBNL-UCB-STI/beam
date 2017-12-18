package beam.utils

import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream

import org.matsim.core.config.Config
import org.slf4j.LoggerFactory

import scala.util.Try
/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils {

  private val logger = LoggerFactory.getLogger(getClass.getName)

  def setConfigOutputFile(outputDirectoryBasePath: String, simulationName: String,config:Config):Unit = {
    /**
      * two cases here:
      * 1. local run when we have multiples runs in the same dir
      * 2. production run with experiment manager when each run configured to have different config folder
       */

    val providedOutputDir = Paths.get(outputDirectoryBasePath)
    if (Files.exists(providedOutputDir) && Files.list(providedOutputDir).count() > 0) {
      val timestamp: String = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format (new java.util.Date () )
      val outputDirectoryName =  simulationName + "_" + timestamp
      val outputDirectory = new File(outputDirectoryBasePath + File.separator + outputDirectoryName)
      outputDirectory.mkdir()
      logger.info(s"Several files have been found in the base output dir, adding timestamp based suffix to current run output. Final output dir: ${outputDirectory.getAbsolutePath}   ")
      config.controler.setOutputDirectory(outputDirectory.getAbsolutePath)
    } else {
      val outputDirectory = new File(outputDirectoryBasePath)
      outputDirectory.mkdir()
      config.controler.setOutputDirectory(outputDirectory.getAbsolutePath)
    }
  }


  def decompress(compressed: Array[Byte]): Option[String] =
    Try {
      val inputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))
      scala.io.Source.fromInputStream(inputStream).mkString
    }.toOption


  def using[A <: { def close(): Unit }, B](resource: A)(f: A => B): B =
    try {
      f(resource)
    } finally {
      resource.close()
    }
}
