package beam.side.speed.parser.data
import java.nio.file.Path

import beam.side.speed.model.Decoder

class Dictionary[T <: Product, K, V](path: Path, f: T => (K, V))(
    implicit decoder: Decoder[T])
    extends DataLoader[T]
    with UnarchivedSource {
  private val dict: Map[K, V] = load(Seq(path)).map(f).toMap

  def apply(key: K): Option[V] = dict.get(key)
}
