package beam.side.route.processing.data

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.Path

import beam.side.route.model.{Encoder, ID}
import beam.side.route.processing.DataWriter
import zio._
import zio.stream.ZStream
import zio.stream.ZStream.Pull

import scala.annotation.tailrec
import scala.reflect.ClassTag

class DataWriterIO(parallel: Int, factor: Int) extends DataWriter[({ type T[A] = RIO[zio.ZEnv, A] })#T, Queue] {
  import Encoder._
  import zio.console._

  private[this] def openFiles(fileRootPath: Path, ids: Set[Int]): RIO[zio.ZEnv, Map[Int, BufferedWriter]] =
    IO(fileRootPath.toFile.mkdir())
      .andThen(IO(ids.map(id => id -> fileRootPath.resolve(s"$id.txt").toFile).toMap))
      .map(files => files.mapValues(f => new BufferedWriter(new FileWriter(f))))

  override def writeFile[A <: Product with ID: Encoder: ClassTag](
    dataFile: Path,
    buffer: Queue[A],
    ids: Set[Int]
  ): RIO[ZEnv, Unit] =
    ZManaged
      .make(openFiles(dataFile, ids))(
        files =>
          UIO.collectAll(files.values.map { bw =>
            UIO {
              bw.flush()
              bw.close()
            }
          })
      )
      .zip(
        ZManaged
          .make(IO.effectTotal(buffer))(q => q.shutdown)
      )
      .use {
        case (files, queue) =>
          ZStream {
            stream.Stream
              .fromQueue[Throwable, A](queue)
              .process
              .mapM { as =>
                val chunkSize = parallel * factor
                Ref.make[Option[Option[Throwable]]](None).flatMap { stateRef =>
                  def loop(acc: Array[String], i: Int, id: Int): Pull[ZEnv, Throwable, Chunk[String]] =
                    as.filterOrFail[Option[Throwable]](e => e.id == id)(Some(new IllegalArgumentException))
                      .foldM(
                        e => {
                          stateRef.set(Some(e)) *> Pull.emit(Chunk.fromArray(acc).take(i))
                        },
                        a => {
                          acc(i) = a.row
                          val i1 = i + 1
                          if (i1 >= chunkSize) Pull.emit(Chunk.fromArray(acc)) else loop(acc, i1, id)
                        }
                      )

                  def first: Pull[ZEnv, Throwable, (Int, Chunk[String])] =
                    as.foldM(e => {
                      stateRef.set(Some(e)) *> ZIO.fail(e)
                    }, a => {
                      val acc: Array[String] = Array.ofDim(chunkSize)
                      acc(0) = a.row
                      loop(acc, 1, a.id).map(ch => a.id -> ch)
                    })

                  IO.succeed {
                    stateRef.get.flatMap {
                      case None    => first
                      case Some(e) => ZIO.fail(e)
                    }
                  }
                }
              }
          }.mapMParUnordered(parallel / 2) {
              case (id, a) =>
                IO.effectTotal {
                  files(id).write(a.mkString("\n"))
                  files(id).newLine()
                }
            }
            .runDrain
      }
}

object DataWriterIO {
  def apply(parallel: Int, factor: Int): DataWriterIO = new DataWriterIO(parallel, factor)
}
