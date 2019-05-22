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
    val householdId = getIfNotNull(rec, "household_id")
    val cars = getIfNotNull(rec, "cars").toInt
    val income = getIfNotNull(rec, "income").toDouble
    val x = getIfNotNull(rec, "x").toDouble
    val y = getIfNotNull(rec, "y").toDouble
    HouseholdInfo(householdId = HouseholdId(householdId), cars = cars, income = income, x = x, y = y)
  }

  private[matsim] def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
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
      personId = PersonId(personId),
      planElementType = planElement,
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = x,
      activityLocationY = y,
      activityEndTime = endTime,
      legMode = mode
    )
  }

  private def toPersonInfo(rec: java.util.Map[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "person_id")
    val householdId = getIfNotNull(rec, "household_id")
    val age = getIfNotNull(rec, "age").toInt
    val rank: Int = 0
    PersonInfo(personId = PersonId(personId), householdId = HouseholdId(householdId), rank = rank, age = age)
  }

  private def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}
