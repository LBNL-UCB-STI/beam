package beam.utils.plan_converter.entities

import java.util

import beam.utils.plan_converter.EntityTransformer

case class Block(
  blockId: String,
  x: Double,
  y: Double
)

object Block extends EntityTransformer[Block] {
  override def transform(rec: util.Map[String, String]): Block = {
    val blockId = getIfNotNull(rec, "block_id")
    val x = getIfNotNull(rec, "x").toDouble
    val y = getIfNotNull(rec, "y").toDouble

    Block(blockId, x, y)
  }
}
