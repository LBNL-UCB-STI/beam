package beam.side.route.processing
import java.nio.file.Path

import beam.side.route.model.RowDecoder

import scala.language.higherKinds

trait DataLoader[F[_]] {
  def loadData[A <: Product: RowDecoder](dataFile: Path): F[A]
}

object DataLoader {
  def apply[F[_]](implicit loader: DataLoader[F]): DataLoader[F] = loader
}
