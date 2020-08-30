package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import beam.utils.scenario.urbansim.censusblock.entities.InputPersonInfo

class PersonReader(path: String) extends BaseCsvReader[InputPersonInfo](path) {
  override val transformer: EntityTransformer[InputPersonInfo] = InputPersonInfo
}
