package beam.utils.plan_converter.reader

import beam.utils.plan_converter.Transformer
import beam.utils.plan_converter.entities.InputHousehold

class HouseHoldReader(path: String) extends BaseCsvReader[InputHousehold](path){
  override val transformer: Transformer[InputHousehold] = InputHousehold
}
