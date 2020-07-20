package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import beam.utils.scenario.urbansim.censusblock.entities.InputPlanElement

class PlanReader(path: String) extends BaseCsvReader[InputPlanElement](path) {
  override val transformer: EntityTransformer[InputPlanElement] = InputPlanElement
}
