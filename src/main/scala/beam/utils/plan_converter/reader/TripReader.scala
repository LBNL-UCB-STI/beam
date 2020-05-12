package beam.utils.plan_converter.reader

import beam.utils.plan_converter.EntityTransformer
import beam.utils.plan_converter.entities.TripElement

class TripReader(path: String) extends BaseCsvReader[TripElement](path) {
  override val transformer: EntityTransformer[TripElement] = TripElement
}
