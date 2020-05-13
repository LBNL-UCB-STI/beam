package beam.utils.plan_converter.reader

import beam.utils.plan_converter.EntityTransformer
import beam.utils.plan_converter.entities.InputHousehold

class HouseHoldReader(path: String) extends BaseCsvReader[InputHousehold](path) {
  override val transformer: EntityTransformer[InputHousehold] = InputHousehold
}
