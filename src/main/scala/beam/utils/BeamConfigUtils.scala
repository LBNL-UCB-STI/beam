package beam.utils

import java.io.File
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._

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

}
