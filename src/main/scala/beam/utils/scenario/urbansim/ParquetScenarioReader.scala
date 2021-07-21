package beam.utils.scenario.urbansim

import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.DataExchange._
import beam.utils.{ParquetReader, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.generic.GenericRecord
import org.apache.commons.lang3.math.NumberUtils

import scala.reflect.ClassTag
import scala.util.Try

object ParquetScenarioReader extends UrbanSimScenarioReader with LazyLogging {

  def main(array: Array[String]): Unit = {
    //    readUnitsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\units.parquet").take(3).foreach(println)
    //    readParcelAttrFile("C:\\repos\\apache_arrow\\py_arrow\\data\\parcel_attr.parquet").take(3).foreach(println)
    //    readBuildingsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\buildings.parquet").take(3).foreach(println)
    //    readPersonsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\persons.parquet").take(3).foreach(println)
    readPlansFile("""C:\temp\2010\plans.parquet""").take(3).foreach(println)
    // readHouseholdsFile("C:\\repos\\apache_arrow\\py_arrow\\data\\households.parquet").take(3).foreach(println)
  }

  def inputType: InputType = InputType.Parquet

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

  private[utils] def readAs[T](path: String, what: String, mapper: GenericRecord => T)(implicit
    ct: ClassTag[T]
  ): Array[T] = {
    val (it, toClose) = ParquetReader.read(path)
    ProfilingUtils.timed(what, x => logger.info(x)) {
      try {
        it.map(mapper).toArray
      } finally {
        toClose.close()
      }
    }
  }

  private[scenario] def toHouseholdInfo(rec: GenericRecord): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "household_id").toString
    val cars = getIfNotNull(rec, "cars").asInstanceOf[Double].toInt
    val unitId = getIfNotNull(rec, "unit_id").toString
    val buildingId = getIfNotNull(rec, "building_id").toString
    val income = getIfNotNull(rec, "income").asInstanceOf[Double]
    HouseholdInfo(householdId = householdId, cars = cars, income = income, unitId = unitId, buildingId = buildingId)
  }

  private[scenario] def toPlanInfo(rec: GenericRecord): PlanElement = {
    // Somehow Plan file has columns in camelCase, not snake_case
    val personId = getIfNotNull(rec, "personId").toString
    val planElement = getIfNotNull(rec, "planElement").toString
    val planElementIndex = getIfNotNull(rec, "planElementIndex").asInstanceOf[Long].toInt
    val activityType = Option(rec.get("activityType")).map(_.toString)
    val x = Option(rec.get("x")).map(_.asInstanceOf[Double])
    val y = Option(rec.get("y")).map(_.asInstanceOf[Double])
    val endTime = Option(rec.get("endTime")).map(_.asInstanceOf[Double])
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

  private[scenario] def toPersonInfo(rec: GenericRecord): PersonInfo = {
    val personId = getIfNotNull(rec, "person_id").toString
    val householdId = getIfNotNull(rec, "household_id").toString
    val age = getIfNotNull(rec, "age").asInstanceOf[Long].toInt
    val isFemaleValue = {
      val value = Try(getIfNotNull(rec, "sex").asInstanceOf[Long]).getOrElse(1L)
      value == 2L
    }
    val excludedModes: String = Try(getIfNotNull(rec, "excludedModes").toString).getOrElse("")
    val rank: Int = 0
    PersonInfo(
      personId = personId,
      householdId = householdId,
      rank = rank,
      age = age,
      excludedModes = excludedModes,
      isFemale = isFemaleValue,
      valueOfTime = Try(NumberUtils.toDouble(getIfNotNull(rec, "valueOfTime").toString, 0d)).getOrElse(0d)
    )
  }

  private[scenario] def toBuildingInfo(rec: GenericRecord): BuildingInfo = {
    val parcelId: String = UrbanSimScenarioReader.fixParcelId(getIfNotNull(rec, "parcel_id").toString)
    val buildingId: String = UrbanSimScenarioReader.fixBuildingId(getIfNotNull(rec, "building_id").toString)
    BuildingInfo(parcelId = parcelId, buildingId = buildingId)
  }

  private[scenario] def toParcelAttribute(rec: GenericRecord): ParcelAttribute = {
    val primaryId = getIfNotNull(rec, "parcel_id").toString
    val x = getIfNotNull(rec, "x").asInstanceOf[Double]
    val y = getIfNotNull(rec, "y").asInstanceOf[Double]
    ParcelAttribute(primaryId = primaryId, x = x, y = y)
  }

  private[scenario] def toUnitInfo(rec: GenericRecord): UnitInfo = {
    val unitId = getIfNotNull(rec, "unit_id").toString
    val buildingId = getIfNotNull(rec, "building_id").toString
    UnitInfo(unitId = unitId, buildingId = buildingId)
  }

  private[scenario] def getIfNotNull(rec: GenericRecord, column: String): AnyRef = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}
