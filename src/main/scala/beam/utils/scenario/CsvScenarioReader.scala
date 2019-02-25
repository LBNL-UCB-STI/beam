package beam.utils.scenario

import beam.utils._
import com.typesafe.scalalogging.LazyLogging
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

object CsvScenarioReader extends ScenarioReader with LazyLogging {

  def main(array: Array[String]): Unit = {

//    readParcelAttrFile("C:\\repos\\apache_arrow\\py_arrow\\data\\parcel_attr.csv").take(3).foreach(println)
//    val buildings = readBuildingsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\buildings.csv")

//    println(s"buildingId: ${buildings.map(_.buildingId).distinct.size}")
//    println(s"parcelId: ${buildings.map(_.parcelId).distinct.size}")
//
//    readPersonsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\persons.csv").take(3).foreach(println)
//    readPlansFile("C:\\repos\\apache_arrow\\py_arrow\\data\\plans.csv").take(3).foreach(println)
    val hh = readHouseholdsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\households.csv")
    println(s"household_id: ${hh.map(_.householdId).distinct.size}")
    println(s"building_id: ${hh.map(_.buildingId).distinct.size}")

    val units = readUnitsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\units.csv")
    println(s"unitId: ${units.map(_.unitId).distinct.size}")
    println(s"buildingId: ${units.map(_.buildingId).distinct.size}")

    val buildings = readBuildingsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\buildings.csv")
    println(s"buildingId: ${buildings.map(_.buildingId).distinct.size}")
    println(s"parcelId: ${buildings.map(_.parcelId).distinct.size}")
  }
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

  def readPlansFile(path: String): Array[PlanInfo] = {
    readAs[PlanInfo](path, "readPlansFile", toPlanInfo)
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
    val cars = getIfNotNull(rec, "cars").toDouble
    val unitId = getIfNotNull(rec, "unit_id")
    val buildingId = getIfNotNull(rec, "building_id")
    val income = getIfNotNull(rec, "income").toDouble
    HouseholdInfo(householdId = householdId, cars = cars, income = income, unitId = unitId, buildingId = buildingId)
  }

  private def toPlanInfo(rec: java.util.Map[String, String]): PlanInfo = {
    // Somehow Plan file has columns in camelCase, not snake_case
    val personId = getIfNotNull(rec, "personId")
    val planElement = getIfNotNull(rec, "planElement")
    val activityType = Option(rec.get("activityType"))
    val x = Option(rec.get("x")).map(_.toDouble)
    val y = Option(rec.get("y")).map(_.toDouble)
    val endTime = Option(rec.get("endTime")).map(_.toDouble)
    val mode = Option(rec.get("mode")).map(_.toString)
    if (planElement == "leg") {
      assert(mode.isDefined, s"planElement = $planElement, but mode = null!")
    }
    PlanInfo(
      personId = personId,
      planElement = planElement,
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
    val rank: Int = 0
    PersonInfo(personId = personId, householdId = householdId, rank = rank, age = age)
  }

  private def toBuildingInfo(rec: java.util.Map[String, String]): BuildingInfo = {
    val parcelId: String = ScenarioReader.fixParcelId(getIfNotNull(rec, "parcel_id"))
    val buildingId: String = ScenarioReader.fixBuildingId(getIfNotNull(rec, "building_id"))
    BuildingInfo(parcelId = parcelId, buildingId = buildingId)
  }

  private def toParcelAttribute(rec: java.util.Map[String, String]): ParcelAttribute = {
    val primaryId = getIfNotNull(rec, "primary_id")
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
