package beam.side.route.processing.data

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.Path

import beam.side.route.model.Encoder
import beam.side.route.processing.DataWriter
import zio._

class DataWriterIO extends DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {
  import Encoder._

  private[this] def openFile(filePath: Path): RIO[zio.ZEnv, BufferedWriter] =
    IO(filePath.toFile).map(f => new BufferedWriter(new FileWriter(f)))

  def writeFile[A <: Product: Encoder](dataFile: Path, buffer: Queue[A]): RIO[ZEnv, Unit] =
    ZManaged
      .fromAutoCloseable(openFile(dataFile))
      .zip(
        ZManaged
          .make(IO.effectTotal(buffer))(q => q.shutdown)
      )
      .use {
        case (file, queue) =>
          stream.Stream
            .fromQueue[Throwable, A](queue)
            .foreach(a => IO.effectTotal(file.write(a.row)).andThen(IO.effectTotal(file.newLine())))
            .flatMap(_ => IO.effectTotal(file.flush()))
      }
}

object DataWriterIO {
  def apply(): DataWriterIO = new DataWriterIO()
}
