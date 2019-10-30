package beam.side.speed.parser.data
import java.io.FileInputStream
import java.nio.file.Path

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

import scala.io.Source

trait UnarchivedSource {

  def read(paths: Seq[Path]): Iterator[String] = {
    paths.iterator
      .map(
        p =>
          new ZipArchiveInputStream(new FileInputStream(p.toFile)) { self =>
            self.getNextZipEntry
        }
      )
      .flatMap(zis => Source.fromInputStream(zis).getLines().drop(1))
  }

}
