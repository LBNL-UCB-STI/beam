package beam.metasim.utils

import java.io.File
import java.text.SimpleDateFormat

import org.matsim.core.config.Config
/**
  * Created by sfeygin on 1/30/17.
  */
object FileUtils {

  def setConfigOutputFile(outputDirectoryBasePath: String, simulationName: String,config:Config):Unit = {
    val timestamp: String = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format (new java.util.Date () )
    val outputDirectoryName =  simulationName + "_" + timestamp
    val outputDirectory = new File(outputDirectoryBasePath + File.separator + outputDirectoryName)
    outputDirectory.mkdir()
    config.controler setOutputDirectory outputDirectory.getAbsolutePath
  }


}
