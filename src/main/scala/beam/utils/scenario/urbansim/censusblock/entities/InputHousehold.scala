package beam.utils.scenario.urbansim.censusblock.entities

import java.util

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class InputHousehold(
  householdId: String,
  income: Int,
  cars: Int,
  blockId: Long
)

object InputHousehold extends EntityTransformer[InputHousehold] {

  override def transform(rec: util.Map[String, String]): InputHousehold = {
    val householdId = getIfNotNull(rec, "household_id")
    val income = getIfNotNull(rec, "income").toInt
    val cars = getIfNotNull(rec, "cars").toInt
    val blockId = getIfNotNull(rec, "block_id").toLong

    InputHousehold(householdId, income, cars, blockId)
  }
}
