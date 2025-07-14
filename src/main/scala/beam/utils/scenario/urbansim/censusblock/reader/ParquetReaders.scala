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

  override protected def transform(record: GenericRecord): InputPlanElement = {
    val personId = record.get("person_id").toString.split("\\.")(0)
    InputPlanElement(
      tripId = Option(record.get("trip_id")).map(_.toString),
      tourId = Option(record.get("tour_id")).map(_.toString),
      personId = personId,
      planElementIndex = record.get("PlanElementIndex").toString.toInt,
      activityElement = ActivityType.determineActivity(record.get("ActivityElement").toString),
      tripMode = Option(record.get("trip_mode")).map(_.toString).filterNot(_ == "nan"),
      ActivityType = Option(record.get("ActivityType")).map(_.toString).filterNot(_ == "nan"),
      x = Option(record.get("x")).map(_.toString.toDouble),
      y = Option(record.get("y")).map(_.toString.toDouble),
      departureTime = Option(record.get("departure_time")).map(_.toString.toDouble),
      expectedDurationMinutes = Option(record.get("trip_dur_min")).map(_.toString.toDouble),
      expectedCostDollars = Option(record.get("trip_cost_dollars")).map(_.toString.toDouble)
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
