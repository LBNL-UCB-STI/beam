package beam.side.speed.parser.data
import java.nio.file.Path

import beam.side.speed.model.Decoder

trait DataLoader[T <: Product] { self: UnarchivedSource =>
  import beam.side.speed.model.SpeedEvents._

  def load(paths: Seq[Path])(implicit dec: Decoder[T]): Iterator[T] = read(paths).map(_.osm)
}
