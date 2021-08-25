package beam.utils.scenario.generic.readers

import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.{HouseholdId, HouseholdInfo, PersonInfo}

import scala.util.Try

trait HouseholdInfoReader {
  def read(path: String): Array[HouseholdInfo]
}

object CsvHouseholdInfoReader extends HouseholdInfoReader {
  import GenericCsvReader._

  override def read(path: String): Array[HouseholdInfo] = {
    val (it, toClose) = readAs[HouseholdInfo](path, toHouseholdInfo, _ => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  private[readers] def toHouseholdInfo(rec: java.util.Map[String, String]): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "householdId")
    HouseholdInfo(
      householdId = HouseholdId(householdId),
      cars = getIfNotNull(rec, "cars").toInt,
      income = getIfNotNull(rec, "incomeValue").toDouble,
      locationX = getIfNotNull(rec, "locationX").toDouble,
      locationY = getIfNotNull(rec, "locationY").toDouble
    )
  }
}
