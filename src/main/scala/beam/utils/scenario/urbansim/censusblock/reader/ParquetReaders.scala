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

  private def isNullOrNanString(str: String): Boolean =
    str.isEmpty || str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("null")

  private def readPlanIndex(record: GenericRecord): Option[Int] = {
    getFieldFromNames(record, "planIndex", "planindex").flatMap {
      case n: java.lang.Number => Some(n.intValue())
      case s: CharSequence =>
        val str = s.toString.trim
        if (isNullOrNanString(str)) None
        else Try(str.toDouble.toInt).toOption
      case _ => None
    }
  }

  private def readPlanScore(record: GenericRecord): Option[Double] = {
    getFieldFromNames(record, "planScore", "planscore").flatMap {
      case n: java.lang.Number => Some(n.doubleValue())
      case s: CharSequence =>
        val str = s.toString.trim
        if (str.isEmpty || str.equalsIgnoreCase("null")) None
        else if (str.equalsIgnoreCase("nan")) Some(Double.NaN)
        else Try(str.toDouble).toOption
      case _ => None
    }
  }

  private def readPlanSelected(record: GenericRecord): Option[Boolean] = {
    getFieldFromNames(record, "planSelected", "planselected").flatMap {
      case b: java.lang.Boolean => Some(b.booleanValue())
      case n: java.lang.Number  => Some(n.intValue() != 0)
      case s: CharSequence =>
        val str = s.toString.trim
        if (isNullOrNanString(str)) None
        else InputPlanElement.parseBoolean(str)
      case _ => None
    }
  }

  override protected def transform(record: GenericRecord): InputPlanElement = {
    val personId = getFieldFromNames(record, "person_id", "personId")
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing person_id in parquet record: $record"))
    val planElementIndex = getFieldFromNames(record, "PlanElementIndex", "planElementIndex")
      .map(_.toString.toInt)
      .getOrElse(throw new IllegalArgumentException(s"Missing planElementIndex in parquet record: $record"))
    val activityElement = getFieldFromNames(record, "ActivityElement", "activityElement", "planElementType")
      .map(v => ActivityType.determineActivity(v.toString))
      .getOrElse(throw new IllegalArgumentException(s"Missing activityElement in parquet record: $record"))

    InputPlanElement(
      tripId = getFieldFromNames(record, "trip_id", "tripId").map(_.toString).filterNot(isNullOrNanString),
      tourId = getFieldFromNames(record, "tour_id", "tourId").map(_.toString).filterNot(isNullOrNanString),
      personId = personId,
      planElementIndex = planElementIndex,
      activityElement = activityElement,
      tripMode = getFieldFromNames(record, "trip_mode", "legMode").map(_.toString).filterNot(isNullOrNanString),
      ActivityType =
        getFieldFromNames(record, "ActivityType", "activityType").map(_.toString).filterNot(isNullOrNanString),
      x = getFieldFromNames(record, "x", "activityLocationX")
        .map(_.toString)
        .filterNot(isNullOrNanString)
        .map(_.toDouble),
      y = getFieldFromNames(record, "y", "activityLocationY")
        .map(_.toString)
        .filterNot(isNullOrNanString)
        .map(_.toDouble),
      departureTime = getFieldFromNames(record, "departure_time", "departureTime", "activityEndTime")
        .map(_.toString)
        .filterNot(isNullOrNanString)
        .map(_.toDouble),
      expectedDurationMinutes =
        getFieldOpt(record, "trip_dur_min").map(_.toString).filterNot(isNullOrNanString).map(_.toDouble),
      expectedCostDollars =
        getFieldOpt(record, "trip_cost_dollars").map(_.toString).filterNot(isNullOrNanString).map(_.toDouble),
      planIndex = readPlanIndex(record),
      planScore = readPlanScore(record),
      planSelected = readPlanSelected(record),
      legDepartureTime = getFieldFromNames(record, "legDepartureTime", "leg_departure_time", "legdeparturetime")
        .map(_.toString)
        .filterNot(isNullOrNanString),
      legTravelTime = getFieldFromNames(record, "legTravelTime", "leg_travel_time", "legtraveltime")
        .map(_.toString)
        .filterNot(isNullOrNanString)
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
