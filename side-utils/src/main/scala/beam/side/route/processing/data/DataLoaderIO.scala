package beam.side.route.processing.data

import java.io._
import java.nio.file.Path

import beam.side.route.model.RowDecoder
import beam.side.route.processing.DataLoader
import zio._
import zio.stream._

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class DataLoaderIO extends DataLoader[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {

  import RowDecoder._

  private[this] def openFile(filePath: Path): RIO[zio.ZEnv, BufferedReader] =
    IO(filePath.toFile).map(f => new BufferedReader(new FileReader(f)))

  def loadData[A <: Product: RowDecoder](
    dataFile: Path,
    buffer: Queue[A],
    headless: Boolean
  ): RIO[zio.ZEnv, Queue[A]] = {
    ZManaged.fromAutoCloseable(openFile(dataFile)).zip(ZManaged.effectTotal(headless)).use {
      case (reader, hl) =>
        for {
          queue <- IO.effectTotal(buffer)
          lines = IO
            .effectTotal(reader.lines().iterator().asScala)
            .map(i => Option(hl).filter(identity).fold(i.drop(1))(_ => i))
          _ <- Stream
            .fromIterator(lines)
            .map(l => Try(l.decode[A]))
            .collect{ case Success(a) => a }
            .foldM(queue)((q, a) => q.offer(a).map(_ => q))
        } yield queue
    }
  }
}

object DataLoaderIO {
  def apply(): DataLoaderIO = new DataLoaderIO
}
