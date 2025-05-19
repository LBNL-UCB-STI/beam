package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.entities._
import beam.utils.FileUtils
import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.util.Try

class CsvPersonReader(path: String) extends BaseCsvReader[InputPersonInfo](path) {

  override val transformer = new EntityTransformer[InputPersonInfo] {

    override def transform(record: java.util.Map[String, String]): InputPersonInfo = {
      InputPersonInfo(
        personId = record.get("person_id"),
        householdId = record.get("household_id"),
        age = record.get("age").toInt,
        sex = Sex.determineSex(record.get("sex").toInt),
        industry = Option(record.get("industry")),
        valueOfTime = Option(record.get("value_of_time")).map(_.toDouble)
      )
    }
  }
}

class CsvPlanReader(path: String) extends BaseCsvReader[InputPlanElement](path) {

  override val transformer = new EntityTransformer[InputPlanElement] {

    override def transform(record: java.util.Map[String, String]): InputPlanElement = {
      val personId = record.get("person_id").split("\\.")(0)
      InputPlanElement(
        tripId = Option(record.get("trip_id")),
        tourId = Option(record.get("tour_id")),
        personId = personId,
        planElementIndex = record.get("PlanElementIndex").toInt,
        activityElement = ActivityType.determineActivity(record.get("ActivityElement")),
        tripMode = Option(record.get("trip_mode")),
        ActivityType = Option(record.get("ActivityType")),
        x = Option(record.get("x")).map(_.toDouble),
        y = Option(record.get("y")).map(_.toDouble),
        departureTime = Option(record.get("departure_time")).map(_.toDouble),
        expectedDurationMinutes = Option(record.get("trip_dur_min")).map(_.toString.toDouble),
        expectedCostDollars = Option(record.get("trip_cost_dollars")).map(_.toString.toDouble)
      )
    }
  }
}

class CsvHouseholdReader(path: String) extends BaseCsvReader[InputHousehold](path) {

  override val transformer = new EntityTransformer[InputHousehold] {

    override def transform(record: java.util.Map[String, String]): InputHousehold = {
      InputHousehold(
        householdId = record.get("household_id"),
        cars = Try(record.get("cars").toInt).getOrElse(record.get("auto_ownership").toInt),
        income = Math.round(record.get("income").toFloat),
        blockId = record.get("block_id").toLong
      )
    }
  }
}

class CsvBlockReader(path: String) extends BaseCsvReader[Block](path) {

  override val transformer = new EntityTransformer[Block] {

    override def transform(record: java.util.Map[String, String]): Block = {
      Block(
        blockId = record.get("block_id").toLong,
        x = record.get("x").toDouble,
        y = record.get("y").toDouble
      )
    }
  }
}
