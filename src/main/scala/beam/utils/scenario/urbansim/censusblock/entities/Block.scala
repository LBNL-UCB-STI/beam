package beam.utils.scenario.urbansim.censusblock.entities

import com.univocity.parsers.common.record.Record

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class Block(
  blockId: Long,
  x: Double,
  y: Double
)

object Block extends EntityTransformer[Block] {

  override def transform(rec: Record): Block = {
    val blockId = getLongIfNotNull(rec, "block_id")
    val x =getDoubleIfNotNull(rec, "x")
    val y = getDoubleIfNotNull(rec, "y")

    Block(blockId, x, y)
  }
}
