package beam.utils.plan_converter.reader

import beam.utils.plan_converter.EntityTransformer
import beam.utils.plan_converter.entities.InputPersonInfo

class PersonReader(path: String) extends BaseCsvReader[InputPersonInfo](path) {
  override val transformer: EntityTransformer[InputPersonInfo] = InputPersonInfo
}
