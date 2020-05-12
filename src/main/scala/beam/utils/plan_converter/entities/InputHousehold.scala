package beam.utils.plan_converter.entities

import java.util

import beam.utils.plan_converter.Transformer

case class InputHousehold(
  householdId: Int,
  income: Int
)

object InputHousehold extends Transformer[InputHousehold] {
  override def transform(rec: util.Map[String, String]): InputHousehold = {
    val householdId = getIfNotNull(rec, "household_id").toInt
    val income = getIfNotNull(rec, "income").toInt

    InputHousehold(householdId, income)
  }
}
