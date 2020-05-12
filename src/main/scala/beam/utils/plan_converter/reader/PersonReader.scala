package beam.utils.plan_converter.reader

import beam.utils.plan_converter.Transformer
import beam.utils.plan_converter.entities.InputPersonInfo

class PersonReader(path: String) extends BaseCsvReader[InputPersonInfo](path) {
  override val transformer: Transformer[InputPersonInfo] = InputPersonInfo
}
