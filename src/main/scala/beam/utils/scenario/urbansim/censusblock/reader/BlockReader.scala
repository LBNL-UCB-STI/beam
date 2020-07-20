package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import beam.utils.scenario.urbansim.censusblock.entities.Block

class BlockReader(path: String) extends BaseCsvReader[Block](path) {
  override val transformer: EntityTransformer[Block] = Block
}
