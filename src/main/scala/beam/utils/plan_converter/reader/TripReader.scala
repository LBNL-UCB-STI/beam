package beam.utils.plan_converter.reader

import beam.utils.plan_converter.Transformer
import beam.utils.plan_converter.entities.TripElement

class TripReader(path: String) extends BaseCsvReader[TripElement](path) {
  override val transformer: Transformer[TripElement] = TripElement
}
