package beam.side.route.processing

import java.nio.file.Path

import beam.side.route.model.Encoder

import scala.language.higherKinds

trait DataWriter[F[_], BUF[_]] {
  def writeFile[A <: Product: Encoder](dataFile: Path, buffer: BUF[A]): F[Unit]
}

object DataWriter {
  def apply[F[_], BUF[_]](implicit writer: DataWriter[F, BUF]): DataWriter[F, BUF] = writer
}
