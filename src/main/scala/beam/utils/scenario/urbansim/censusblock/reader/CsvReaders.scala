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

    private def parseBoolean(s: String): Option[Boolean] = {
      s.trim.toLowerCase match {
        case "true" | "t" | "1" | "1.0"  => Some(true)
        case "false" | "f" | "0" | "0.0" => Some(false)
        case other                       => Try(other.toBoolean).toOption
      }
    }

    private def getOptionalNonEmpty(record: java.util.Map[String, String], key: String): Option[String] =
      Option(record.get(key)).map(_.trim).filter(_.nonEmpty)

    override def transform(record: java.util.Map[String, String]): InputPlanElement = {
      val personId = record.get("person_id").split("\\.")(0)
      val planIndex = getOptionalNonEmpty(record, "planIndex")
        .orElse(getOptionalNonEmpty(record, "planindex"))
        .flatMap(s => Try(s.toDouble.toInt).toOption)
      val planScore = getOptionalNonEmpty(record, "planScore")
        .orElse(getOptionalNonEmpty(record, "planscore"))
        .flatMap(s => Try(s.toDouble).toOption)
      val planSelected = getOptionalNonEmpty(record, "planSelected")
        .orElse(getOptionalNonEmpty(record, "planselected"))
        .flatMap(parseBoolean)

      InputPlanElement(
        tripId = Option(record.get("trip_id")),
        tourId = Option(record.get("tour_id")),
        personId = personId,
        planElementIndex = record.get("PlanElementIndex").toInt,
        activityElement = ActivityType.determineActivity(record.get("ActivityElement")),
        tripMode = Option(record.get("trip_mode")),
        ActivityType = Option(record.get("ActivityType")),
        x = getOptionalNonEmpty(record, "x").map(_.toDouble),
        y = getOptionalNonEmpty(record, "y").map(_.toDouble),
        departureTime = getOptionalNonEmpty(record, "departure_time").map(_.toDouble),
        expectedDurationMinutes = getOptionalNonEmpty(record, "trip_dur_min").flatMap(s => Try(s.toDouble).toOption),
        expectedCostDollars = getOptionalNonEmpty(record, "trip_cost_dollars").flatMap(s => Try(s.toDouble).toOption),
        planIndex = planIndex,
        planScore = planScore,
        planSelected = planSelected
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
