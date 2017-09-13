package beam.utils

import java.io.{ByteArrayInputStream, File}
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream

import org.matsim.core.config.Config

import scala.util.Try
/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils {

  def setConfigOutputFile(outputDirectoryBasePath: String, simulationName: String,config:Config):Unit = {
    val timestamp: String = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format (new java.util.Date () )
    val outputDirectoryName =  simulationName + "_" + timestamp
    val outputDirectory = new File(outputDirectoryBasePath + File.separator + outputDirectoryName)
    outputDirectory.mkdir()
    config.controler.setOutputDirectory(outputDirectory.getAbsolutePath)
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
