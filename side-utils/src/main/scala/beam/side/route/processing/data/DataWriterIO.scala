package beam.side.route.processing.data

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.Path

import beam.side.route.model.Encoder
import beam.side.route.processing.DataWriter
import zio._

class DataWriterIO(parallel: Int, factor: Int) extends DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {
  import Encoder._
  import zio.console._

  private[this] def openFile(filePath: Path): RIO[zio.ZEnv, BufferedWriter] =
    IO(filePath.toFile).map(f => new BufferedWriter(new FileWriter(f)))

  def writeFile[A <: Product: Encoder](dataFile: Path, buffer: Queue[A]): RIO[ZEnv, Unit] =
    ZManaged
      .make(openFile(dataFile))(a => UIO{
        a.flush()
        a.close()
      })
      .zip(
        ZManaged
          .make(IO.effectTotal(buffer))(q => q.shutdown)
      )
      .use {
        case (file, queue) =>
          stream.Stream
            .fromQueue[Throwable, A](queue)
            .map(_.row)
            .chunkN(parallel * factor)
            .mapMParUnordered(parallel/2)(a => IO.effectTotal{
              file.write(a.mkString("\n"))
              file.newLine()
            })
            .runDrain
      }
}

object DataWriterIO {
  def apply(parallel: Int, factor: Int): DataWriterIO = new DataWriterIO(parallel, factor)
}
