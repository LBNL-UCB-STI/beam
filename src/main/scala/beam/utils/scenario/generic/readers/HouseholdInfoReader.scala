package beam.utils.scenario.generic.readers

import java.io.Closeable

import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.{HouseholdId, HouseholdInfo, PersonInfo}

import scala.util.Try

trait HouseholdInfoReader {
  def read(path: String): Array[HouseholdInfo]

  def readWithFilter(path: String, filter: HouseholdInfo => Boolean): (Iterator[HouseholdInfo], Closeable)
}

object CsvHouseholdInfoReader extends HouseholdInfoReader {
  import GenericCsvReader._

  override def read(path: String): Array[HouseholdInfo] = {
    val (it, toClose) = readAs[HouseholdInfo](path, toHouseholdInfo, x => true)
    try {
      it.toArray
    } finally {
      Try(toClose.close())
    }
  }

  override def readWithFilter(path: String, filter: HouseholdInfo => Boolean): (Iterator[HouseholdInfo], Closeable) = {
    readAs[HouseholdInfo](path, toHouseholdInfo, filter)
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
