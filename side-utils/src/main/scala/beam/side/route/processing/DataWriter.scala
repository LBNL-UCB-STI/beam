package beam.side.route.processing

import java.nio.file.Path

import beam.side.route.model.{Encoder, ID}

import scala.language.higherKinds
import scala.reflect.ClassTag

trait DataWriter[F[_], BUF[_]] {
  def writeFile[A <: Product with ID: Encoder: ClassTag](dataFile: Path, buffer: BUF[A], ids: Set[Int]): F[Unit]
}

object DataWriter {
  def apply[F[_], BUF[_]](implicit writer: DataWriter[F, BUF]): DataWriter[F, BUF] = writer
}
