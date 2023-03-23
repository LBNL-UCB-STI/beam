package beam.utils.scenario.urbansim.censusblock.entities

import com.univocity.parsers.common.record.Record

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

case class InputHousehold(
  householdId: String,
  income: Int,
  cars: Int,
  blockId: Long
)

object InputHousehold extends EntityTransformer[InputHousehold] {

  override def transform(rec: Record): InputHousehold = {
    try {
      val householdId = getStringIfNotNull(rec, "household_id")
      val income = Math.round(getFloatIfNotNull(rec, "income"))
      val cars = getIntIfNotNull(rec, "cars", Some("auto_ownership"))
      val blockId = getLongIfNotNull(rec, "block_id")

      InputHousehold(householdId, income, cars, blockId)
    } catch {
      case nfe: NumberFormatException => println(s"ERROR: ${rec.getString("income")}"); throw nfe
    }
  }
}
