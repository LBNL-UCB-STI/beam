package beam.side.speed.parser

import java.nio.file.Path

import beam.side.speed.model.Decoder

trait DataLoader[T <: Product] { self: UnarchivedSource =>
  import beam.side.speed.model.SpeedEvents._

  def load(path: Path)(implicit dec: Decoder[T]): Iterator[T] = read(path).map(_.osm)
}
