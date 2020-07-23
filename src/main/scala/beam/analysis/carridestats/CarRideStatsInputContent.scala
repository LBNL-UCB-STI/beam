package beam.analysis.carridestats

import java.net.URL
import java.nio.file.{Files, Path}

sealed trait InputContentParam extends CarRideStatsParam

case class InputContentFromFile(path: Path) extends InputContentParam {
  if (!Files.isRegularFile(path)) {
    throw new IllegalArgumentException(s"[${path.toString}] is not a valid file.")
  }

  override def arguments: Seq[String] = {
    Seq("--inputFile", path.getFileName.toAbsolutePath.toString)
  }
}

case class InputContentFromUrl(url: URL) extends InputContentParam {
  override def arguments: Seq[String] = Seq("--inputFile", url.toString)
}
