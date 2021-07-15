package beam.utils.scenario.urbansim.censusblock.entities

import java.util

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class Block(
  blockId: Long,
  x: Double,
  y: Double
)

object Block extends EntityTransformer[Block] {

  override def transform(rec: util.Map[String, String]): Block = {
    val blockId = getIfNotNull(rec, "block_id").toLong
    val x = getIfNotNull(rec, "x").toDouble
    val y = getIfNotNull(rec, "y").toDouble

    Block(blockId, x, y)
  }
}
