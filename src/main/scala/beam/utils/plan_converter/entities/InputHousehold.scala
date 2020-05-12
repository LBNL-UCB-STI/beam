package beam.utils.plan_converter.entities

import java.util

import beam.utils.plan_converter.EntityTransformer

case class InputHousehold(
  householdId: String,
  income: Int,
  cars: Int,
  blockId: String
)

object InputHousehold extends EntityTransformer[InputHousehold] {
  override def transform(rec: util.Map[String, String]): InputHousehold = {
    val householdId = getIfNotNull(rec, "household_id")
    val income = getIfNotNull(rec, "income").toInt
    val cars = getIfNotNull(rec, "cars").toInt
    val blockId = getIfNotNull(rec, "block_id")

    InputHousehold(householdId, income, cars, blockId)
  }
}
