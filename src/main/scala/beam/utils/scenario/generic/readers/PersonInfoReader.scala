package beam.utils.scenario.generic.readers

import java.io.Closeable

import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}
import org.apache.commons.lang3.math.NumberUtils

import scala.util.Try

trait PersonInfoReader {
  def read(path: String): Array[PersonInfo]

  def readWithFilter(path: String, filter: PersonInfo => Boolean): (Iterator[PersonInfo], Closeable)
}

object CsvPersonInfoReader extends PersonInfoReader {
  import GenericCsvReader._

  override def read(path: String): Array[PersonInfo] = {
    val (it, toClose) = readAs[PersonInfo](path, toPersonInfo, _ => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  override def readWithFilter(path: String, filter: PersonInfo => Boolean): (Iterator[PersonInfo], Closeable) = {
    readAs[PersonInfo](path, toPersonInfo, filter)
  }

  private[readers] def toPersonInfo(rec: java.util.Map[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "personId")
    val householdId = getIfNotNull(rec, "householdId")
    val age = getIfNotNull(rec, "age").toInt
    val isFemale = getIfNotNull(rec, "isFemale").toBoolean
    val rank = getIfNotNull(rec, "householdRank").toInt
    val excludedModes = getOrDefault(rec, "excludedModes", "").split(",")
    val rideHailServiceSubscription = getOrDefault(rec, "rideHailServiceSubscription", "").split(',')
    val industry = Option(rec.get("industry"))
    val valueOfTime = NumberUtils.toDouble(getOrDefault(rec, "valueOfTime", "0"), 0d)
    PersonInfo(
      personId = PersonId(personId),
      householdId = HouseholdId(householdId),
      rank = rank,
      age = age,
      excludedModes = excludedModes,
      rideHailServiceSubscription = rideHailServiceSubscription,
      isFemale = isFemale,
      valueOfTime = valueOfTime,
      industry = industry
    )
  }
}
