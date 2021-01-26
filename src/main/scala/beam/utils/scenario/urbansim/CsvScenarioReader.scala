package beam.utils.scenario.urbansim

import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.DataExchange._
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.math.NumberUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag
import scala.util.Try

object CsvScenarioReader extends UrbanSimScenarioReader with LazyLogging {

  def inputType: InputType = InputType.CSV

  def readUnitsFile(path: String): Array[UnitInfo] = {
    readAs[UnitInfo](path, "readUnitsFile", toUnitInfo)
  }

  def readParcelAttrFile(path: String): Array[ParcelAttribute] = {
    readAs[ParcelAttribute](path, "readParcelAttrFile", toParcelAttribute)
  }

  def readBuildingsFile(path: String): Array[BuildingInfo] = {
    readAs[BuildingInfo](path, "readBuildingsFile", toBuildingInfo)
  }

  def readPersonsFile(path: String): Array[PersonInfo] = {
    readAs[PersonInfo](path, "readPersonsFile", toPersonInfo)
  }

  def readPlansFile(path: String): Array[PlanElement] = {
    readAs[PlanElement](path, "readPlansFile", toPlanInfo)
  }

  def readHouseholdsFile(path: String): Array[HouseholdInfo] = {
    readAs[HouseholdInfo](path, "readHouseholdsFile", toHouseholdInfo)
  }

  private[utils] def readAs[T](path: String, what: String, mapper: java.util.Map[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => logger.info(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private def toHouseholdInfo(rec: java.util.Map[String, String]): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "household_id")
    val cars = getIfNotNull(rec, "cars").toDouble.toInt
    val unitId = getIfNotNull(rec, "unit_id")
    val buildingId = getIfNotNull(rec, "building_id")
    val income = getIfNotNull(rec, "income").toDouble
    HouseholdInfo(householdId = householdId, cars = cars, income = income, unitId = unitId, buildingId = buildingId)
  }

  private def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
    // Somehow Plan file has columns in camelCase, not snake_case
    val personId = getIfNotNull(rec, "personId")
    val planElement = getIfNotNull(rec, "planElement")
    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
    val activityType = Option(rec.get("activityType"))
    val x = Option(rec.get("x")).map(_.toDouble)
    val y = Option(rec.get("y")).map(_.toDouble)
    val endTime = Option(rec.get("endTime")).map(_.toDouble)
    val mode = Option(rec.get("mode")).map(_.toString)
    PlanElement(
      personId = personId,
      planElement = planElement,
      planElementIndex = planElementIndex,
      activityType = activityType,
      x = x,
      y = y,
      endTime = endTime,
      mode = mode
    )
  }

  private def toPersonInfo(rec: java.util.Map[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "person_id")
    val householdId = getIfNotNull(rec, "household_id")
    val age = getIfNotNull(rec, "age").toInt
    val isFemaleValue: Boolean = {
      val value = getIfNotNull(rec, "sex")
      value == "2" || value == "F"
    }
    val excludedModes = Try(getIfNotNull(rec, "excludedModes")).getOrElse("")
    val rank: Int = 0
    PersonInfo(
      personId = personId,
      householdId = householdId,
      rank = rank,
      age = age,
      excludedModes = excludedModes,
      isFemale = isFemaleValue,
      valueOfTime = Try(NumberUtils.toDouble(getIfNotNull(rec, "valueOfTime"), 0D)).getOrElse(0D)
    )
  }

  private def toBuildingInfo(rec: java.util.Map[String, String]): BuildingInfo = {
    val parcelId: String = UrbanSimScenarioReader.fixParcelId(getIfNotNull(rec, "parcel_id"))
    val buildingId: String = UrbanSimScenarioReader.fixBuildingId(getIfNotNull(rec, "building_id"))
    BuildingInfo(parcelId = parcelId, buildingId = buildingId)
  }

  private def toParcelAttribute(rec: java.util.Map[String, String]): ParcelAttribute = {
    val primaryId = getIfNotNull(rec, "parcel_id")
    val x = getIfNotNull(rec, "x").toDouble
    val y = getIfNotNull(rec, "y").toDouble
    ParcelAttribute(primaryId = primaryId, x = x, y = y)
  }

  private def toUnitInfo(rec: java.util.Map[String, String]): UnitInfo = {
    val unitId = getIfNotNull(rec, "unit_id")
    val buildingId = getIfNotNull(rec, "building_id")
    UnitInfo(unitId = unitId, buildingId = buildingId)
  }
  private def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}
