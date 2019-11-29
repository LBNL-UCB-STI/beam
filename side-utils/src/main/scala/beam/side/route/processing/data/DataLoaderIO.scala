package beam.side.route.processing.data

import java.io._
import java.nio.file.Path

import beam.side.route.model.RowDecoder
import beam.side.route.processing.DataLoader
import zio._
import zio.stream._

import scala.collection.JavaConverters._

class DataLoaderIO extends DataLoader[({ type T[A] = RIO[zio.ZEnv, Queue[A]] })#T] {

  import RowDecoder._

  private[this] def openFile(filePath: Path): RIO[zio.ZEnv, BufferedReader] =
    IO(filePath.toFile).map(f => new BufferedReader(new FileReader(f)))

  def loadData[A <: Product: RowDecoder](dataFile: Path): RIO[zio.ZEnv, Queue[A]] = {
    ZManaged.fromAutoCloseable(openFile(dataFile)).use { reader =>
      for {
        queue <- Queue.unbounded[A]
        _ <- Stream
          .fromIterator(IO.effectTotal(reader.lines().iterator().asScala))
          .map(_.decode[A])
          .foreach(a => queue.offer(a).fork.unit)
          .fork
      } yield queue
    }
  }
}

object DataLoaderIO {
  def apply(): DataLoaderIO = new DataLoaderIO
}
