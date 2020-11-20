package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import beam.utils.scenario.urbansim.censusblock.entities.TripElement

class TripReader(path: String) extends BaseCsvReader[TripElement](path) {
  override val transformer: EntityTransformer[TripElement] = TripElement
}
