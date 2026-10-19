package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.entities._
import org.apache.avro.generic.GenericRecord
import org.apache.avro.AvroRuntimeException

import scala.util.Try

class ParquetPersonReader(path: String) extends BaseParquetReader[InputPersonInfo](path) {

  override protected def transform(record: GenericRecord): InputPersonInfo = {
    val industryField =
      try {
        Option(record.get("industry")).map(_.toString)
      } catch {
        case _: AvroRuntimeException => None // Field doesn't exist in schema
      }

    InputPersonInfo(
      personId = record.get("person_id").toString.split("\\.")(0),
      householdId = record.get("household_id").toString.split("\\.")(0),
      age = record.get("age").toString.toDouble.toInt,
      sex = Sex.determineSex(record.get("sex").toString.toDouble.toInt),
      industry = industryField,
      valueOfTime = Try(record.get("value_of_time")).map(_.toString.toDouble).toOption // TODO: probably a better way
    )
  }
}

class ParquetPlanReader(path: String) extends BaseParquetReader[InputPlanElement](path) {

  private def getFieldOpt(record: GenericRecord, fieldName: String): Option[AnyRef] = {
    val schema = record.getSchema
    if (schema != null && schema.getField(fieldName) != null) {
      Try(Option(record.get(fieldName))).toOption.flatten
    } else {
      None
    }
  }

  private def getFieldFromNames(record: GenericRecord, names: String*): Option[AnyRef] = {
    names.iterator.flatMap(name => getFieldOpt(record, name)).find(_ => true)
  }

  private def readPlanIndex(record: GenericRecord): Option[Int] = {
    getFieldFromNames(record, "planIndex", "planindex").flatMap {
      case n: java.lang.Number => Some(n.intValue())
      case s: CharSequence =>
        val str = s.toString.trim
        if (str.isEmpty || str == "nan" || str == "null") None
        else Try(str.toDouble.toInt).toOption
      case _ => None
    }
  }

  private def readPlanScore(record: GenericRecord): Option[Double] = {
    getFieldFromNames(record, "planScore", "planscore").flatMap {
      case n: java.lang.Number => Some(n.doubleValue())
      case s: CharSequence =>
        val str = s.toString.trim
        if (str.isEmpty || str == "nan" || str == "null") None
        else Try(str.toDouble).toOption
      case _ => None
    }
  }

  private def readPlanSelected(record: GenericRecord): Option[Boolean] = {
    getFieldFromNames(record, "planSelected", "planselected").flatMap {
      case b: java.lang.Boolean => Some(b.booleanValue())
      case n: java.lang.Number  => Some(n.intValue() != 0)
      case s: CharSequence =>
        val str = s.toString.trim.toLowerCase
        if (str.isEmpty || str == "nan" || str == "null") None
        else str match {
          case "true" | "t" | "1" | "1.0"  => Some(true)
          case "false" | "f" | "0" | "0.0" => Some(false)
          case other                       => Try(other.toBoolean).toOption
        }
      case _ => None
    }
  }

  override protected def transform(record: GenericRecord): InputPlanElement = {
    val personId = record.get("person_id").toString.split("\\.")(0)
    InputPlanElement(
      tripId = Option(record.get("trip_id")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null"),
      tourId = Option(record.get("tour_id")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null"),
      personId = personId,
      planElementIndex = record.get("PlanElementIndex").toString.toInt,
      activityElement = ActivityType.determineActivity(record.get("ActivityElement").toString),
      tripMode = Option(record.get("trip_mode")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null"),
      ActivityType = Option(record.get("ActivityType")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null"),
      x = Option(record.get("x")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null").map(_.toDouble),
      y = Option(record.get("y")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null").map(_.toDouble),
      departureTime = Option(record.get("departure_time")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null").map(_.toDouble),
      expectedDurationMinutes = Option(record.get("trip_dur_min")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null").map(_.toDouble),
      expectedCostDollars = Option(record.get("trip_cost_dollars")).map(_.toString).filterNot(s => s.isEmpty || s == "nan" || s == "null").map(_.toDouble),
      planIndex = readPlanIndex(record),
      planScore = readPlanScore(record),
      planSelected = readPlanSelected(record)
    )
  }
}

class ParquetHouseholdReader(path: String) extends BaseParquetReader[InputHousehold](path) {

  override protected def transform(record: GenericRecord): InputHousehold = {
    InputHousehold(
      householdId = record.get("household_id").toString.split("\\.")(0),
      cars = Try(record.get("cars")).getOrElse(record.get("auto_ownership")).toString.toDouble.toInt,
      income = record.get("income").toString.toDouble.toInt,
      blockId = record.get("block_id").toString.toLong
    )
  }
}

class ParquetBlockReader(path: String) extends BaseParquetReader[Block](path) {

  override protected def transform(record: GenericRecord): Block = {
    Block(
      blockId = record.get("block_id").toString.toLong,
      x = record.get("x").toString.toDouble,
      y = record.get("y").toString.toDouble
    )
  }
}
