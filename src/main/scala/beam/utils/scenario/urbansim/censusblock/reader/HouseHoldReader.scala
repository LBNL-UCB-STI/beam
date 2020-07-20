package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import beam.utils.scenario.urbansim.censusblock.entities.InputHousehold

class HouseHoldReader(path: String) extends BaseCsvReader[InputHousehold](path) {
  override val transformer: EntityTransformer[InputHousehold] = InputHousehold
}
