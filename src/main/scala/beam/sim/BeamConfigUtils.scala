package beam.sim

import java.io.File
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory

import scala.collection.JavaConverters._

object BeamConfigUtils {

  def parseFileSubstitutingInputDirectory(fileName: String): com.typesafe.config.Config = {
    val file = Paths.get(fileName).toFile
    parseFileSubstitutingInputDirectory(file)
  }

  def parseFileSubstitutingInputDirectory(file: File): com.typesafe.config.Config = {
    ConfigFactory.parseFile(file)
      .withFallback(ConfigFactory.parseMap(Map("beam.inputDirectory" -> file.getAbsoluteFile.getParent).asJava))
      .resolve
  }

}
