package beam.utils.plan_converter.reader

import beam.utils.plan_converter.EntityTransformer
import beam.utils.plan_converter.entities.Block

class BlockReader(path: String) extends BaseCsvReader[Block](path) {
  override val transformer: EntityTransformer[Block] = Block
}
