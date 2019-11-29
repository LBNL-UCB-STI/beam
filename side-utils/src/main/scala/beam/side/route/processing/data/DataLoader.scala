package beam.side.route.processing.data

import java.io.{BufferedInputStream, FileInputStream, InputStream}
import java.nio.file.Path

import zio._
import zio.stream._

class DataLoader(dataFile: Path) {

  private[this] def openFile(filePath: Path): Task[InputStream] =
    IO(filePath.toFile).map(f => new BufferedInputStream(new FileInputStream(f)))

  private[this] def closeFile(fs: InputStream): UIO[Unit] = UIO(fs.close())

  private[this] val readFileZIO: ZManaged[Any, Throwable, InputStream] = {
    ZManaged.fromAutoCloseable(openFile(dataFile))
  }

}
