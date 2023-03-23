package beam.utils.scenario.urbansim.censusblock.entities

import com.univocity.parsers.common.record.Record

import beam.utils.scenario.urbansim.censusblock.EntityTransformer

import scala.annotation.switch

sealed trait Sex {
  def isFemale: Boolean
}

case object Male extends Sex {
  override val isFemale: Boolean = false
}

case object Female extends Sex {
  override val isFemale: Boolean = true
}

object Sex {

  def determineSex(sex: Int): Sex = (sex: @switch) match {
    case 1 => Male
    case 2 => Female
  }
}

case class InputPersonInfo(
  personId: String,
  householdId: String,
  age: Int,
  sex: Sex,
  industry: Option[String]
)

object InputPersonInfo extends EntityTransformer[InputPersonInfo] {

  override def transform(rec: Record): InputPersonInfo = {
    val personId = getStringIfNotNull(rec, "person_id")
    val householdId = getStringIfNotNull(rec, "household_id")
    val age = getIntIfNotNull(rec, "age")
    val sex = Sex.determineSex(getIntIfNotNull(rec, "sex"))
    val industry = getStringOptional(rec, "industry")

    InputPersonInfo(personId, householdId, age, sex, industry)
  }
}
