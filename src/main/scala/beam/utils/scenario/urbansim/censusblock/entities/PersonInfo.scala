package beam.utils.scenario.urbansim.censusblock.entities

import java.util
import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import org.apache.commons.lang3.math.NumberUtils

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
  industry: Option[String],
  valueOfTime: Option[Double]
)

object InputPersonInfo extends EntityTransformer[InputPersonInfo] {

  override def transform(rec: util.Map[String, String]): InputPersonInfo = {
    val personId = getIfNotNull(rec, "person_id")
    val householdId = getIfNotNull(rec, "household_id")
    val age = getIfNotNull(rec, "age").toInt
    val sex = Sex.determineSex(getIfNotNull(rec, "sex").toInt)
    val industry = Option(rec.get("industry"))
    val valueOfTime = Option(NumberUtils.toDouble(rec.get("value_of_time")))

    InputPersonInfo(personId, householdId, age, sex, industry, valueOfTime)
  }
}
