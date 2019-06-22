package beam.side.speed.parser

import java.io.FileInputStream
import java.nio.file.Path

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

import scala.io.Source

trait UnarchivedSource {

  def read(path: Path): Iterator[String] = {
    val zis = new ZipArchiveInputStream(new FileInputStream(path.toFile))
    zis.getNextZipEntry
    Source.fromInputStream(zis).getLines().drop(1)
  }

}
