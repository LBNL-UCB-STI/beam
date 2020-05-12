package beam.utils.plan_converter.entities

import java.util

import beam.utils.plan_converter.Transformer

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
  personId: Int,
  householdId: Int,
  age: Int,
  sex: Sex
)

object InputPersonInfo extends Transformer[InputPersonInfo] {
  override def transform(rec: util.Map[String, String]): InputPersonInfo = {
    val personId = getIfNotNull(rec, "person_id").toInt
    val householdId = getIfNotNull(rec, "household_id").toInt
    val age = getIfNotNull(rec, "age").toInt
    val sex = Sex.determineSex(getIfNotNull(rec, "sex").toInt)

    InputPersonInfo(personId, householdId, age, sex)
  }
}
