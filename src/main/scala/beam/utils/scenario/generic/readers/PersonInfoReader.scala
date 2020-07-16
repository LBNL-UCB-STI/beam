package beam.utils.scenario.generic.readers

import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}
import org.apache.commons.lang3.math.NumberUtils

import scala.util.Try

trait PersonInfoReader {
  def read(path: String): Array[PersonInfo]
}

object CsvPersonInfoReader extends PersonInfoReader {
  import GenericCsvReader._
  override def read(path: String): Array[PersonInfo] = {
    val (it, toClose) = readAs[PersonInfo](path, toPersonInfo, x => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  private[readers] def toPersonInfo(rec: java.util.Map[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "personId")
    val householdId = getIfNotNull(rec, "householdId")
    val age = getIfNotNull(rec, "age").toInt
    val isFemale = getIfNotNull(rec, "isFemale").toBoolean
    val rank = getIfNotNull(rec, "householdRank").toInt
    val industry = getIfNotNull(rec, "industry")
    val valueOfTime = NumberUtils.toDouble(Try(getIfNotNull(rec, "valueOfTime")).getOrElse("0"), 0D)
    PersonInfo(
      personId = PersonId(personId),
      householdId = HouseholdId(householdId),
      rank = rank,
      age = age,
      isFemale = isFemale,
      industry = industry,
      valueOfTime = valueOfTime
    )
  }
}
