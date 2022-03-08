package beam.utils

import java.nio.file.{Files, Path}

import scala.util.Random

import org.apache.commons.io.FileUtils.deleteQuietly

object FixtureUtils {

  def usingTemporaryTextFileWithContent[B](content: String)(f: Path => B): B = {
    val prefix = Random.alphanumeric.filterNot(_.isDigit).take(5).toList.mkString
    val tmpFile: Path = Files.createTempFile(prefix, "txt")
    try {
      FileUtils.writeToFile(tmpFile.toString, Iterator(content))
      f(tmpFile)
    } finally {
      deleteQuietly(tmpFile.toFile)
    }
  }

}
