package beam.utils.plan_converter.reader

import beam.utils.plan_converter.Transformer
import beam.utils.plan_converter.entities.InputPlanElement

class PlanReader(path: String) extends BaseCsvReader[InputPlanElement](path) {
  override val transformer: Transformer[InputPlanElement] = InputPlanElement
}
