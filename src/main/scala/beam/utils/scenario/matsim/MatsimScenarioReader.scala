package beam.utils.scenario.matsim

import beam.utils.{FileUtils, ProfilingUtils}
import beam.utils.scenario._
import com.typesafe.scalalogging.LazyLogging
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

trait MatsimScenarioReader {
  def inputType: InputType
  def readPersonsFile(path: String): Array[PersonInfo]
  def readPlansFile(path: String): Array[PlanElement]
  def readHouseholdsFile(path: String): Array[HouseholdInfo]
}

object CsvScenarioReader extends MatsimScenarioReader with LazyLogging {
  override def inputType: InputType = InputType.CSV

  override def readPersonsFile(path: String): Array[PersonInfo] = {
    readAs[PersonInfo](path, "readPersonsFile", toPersonInfo)
  }
  override def readPlansFile(path: String): Array[PlanElement] = {
    readAs[PlanElement](path, "readPlansFile", toPlanInfo)

  }
  override def readHouseholdsFile(path: String): Array[HouseholdInfo] = {
    readAs[HouseholdInfo](path, "readHouseholdsFile", toHouseholdInfo)
  }

  private[matsim] def readAs[T](path: String, what: String, mapper: java.util.Map[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => logger.info(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private[matsim] def toHouseholdInfo(rec: java.util.Map[String, String]): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "householdId")
    val cars = getIfNotNull(rec, "cars", "0").toInt
    val income = getIfNotNull(rec, "incomeValue").toDouble
    val x = getIfNotNull(rec, "locationX").toDouble
    val y = getIfNotNull(rec, "locationY").toDouble
    HouseholdInfo(householdId = HouseholdId(householdId), cars = cars, income = income, x = x, y = y)
  }

  private[matsim] def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
    // Somehow Plan file has columns in camelCase, not snake_case
    val personId = getIfNotNull(rec, "personId")
    val planElementType = getIfNotNull(rec, "planElementType")
    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
    val activityType = Option(rec.get("activityType"))
    PlanElement(
      personId = PersonId(personId),
      planElementType = planElementType,
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = Option(rec.get("activityLocationX")).map(_.toDouble),
      activityLocationY = Option(rec.get("activityLocationY")).map(_.toDouble),
      activityEndTime = Option(rec.get("activityEndTime")).map(_.toDouble),
      legMode = Option(rec.get("legMode")).map(_.toString)
    )
  }

  private def toPersonInfo(rec: java.util.Map[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "personId")
    val householdId = getIfNotNull(rec, "householdId")
    val age = getIfNotNull(rec, "age").toInt
    val rank: Int = 0
    PersonInfo(personId = PersonId(personId), householdId = HouseholdId(householdId), rank = rank, age = age)
  }

  private def getIfNotNull(rec: java.util.Map[String, String], column: String, default: String = null): String = {
    val v = rec.getOrDefault(column, default)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}
