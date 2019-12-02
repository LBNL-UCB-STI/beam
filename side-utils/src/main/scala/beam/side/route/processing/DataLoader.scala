package beam.side.route.processing
import java.nio.file.Path

import beam.side.route.model.RowDecoder

import scala.language.higherKinds

trait DataLoader[F[_], BUF[_]] {
  def loadData[A <: Product: RowDecoder](dataFile: Path, buffer: BUF[A], headless: Boolean): F[A]
}

object DataLoader {
  def apply[F[_], BUF[_]](implicit loader: DataLoader[F, BUF]): DataLoader[F, BUF] = loader
}
