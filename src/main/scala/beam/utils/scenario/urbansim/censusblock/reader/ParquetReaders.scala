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

    val age = record.get("age") match {
      case n: java.lang.Number => n.intValue()
      case other               => Try(other.toString.toDouble.toInt).getOrElse(0)
    }
    val sex = record.get("sex") match {
      case n: java.lang.Number => Sex.determineSex(n.intValue())
      case other               => Sex.determineSex(Try(other.toString.toDouble.toInt).getOrElse(0))
    }
    val valueOfTime = Try(record.get("value_of_time")).toOption.flatMap {
      case null                => None
      case n: java.lang.Number => Some(n.doubleValue())
      case other               => Try(other.toString.toDouble).toOption
    }

    InputPersonInfo(
      personId = record.get("person_id").toString.split("\\.")(0),
      householdId = record.get("household_id").toString.split("\\.")(0),
      age = age,
      sex = sex,
      industry = industryField,
      valueOfTime = valueOfTime
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

  private def readDouble(opt: Option[AnyRef]): Option[Double] = {
    opt.flatMap {
      case n: java.lang.Number => Some(n.doubleValue())
      case s: CharSequence =>
        val str = s.toString.trim
        if (isNullOrNanString(str)) None
        else Try(str.toDouble).toOption
      case other => Try(other.toString.toDouble).toOption
    }
  }

  override protected def transform(record: GenericRecord): InputPlanElement = {
    val personId = getFieldFromNames(record, "person_id", "personId")
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing person_id in parquet record: $record"))
    val planElementIndex = getFieldFromNames(record, "PlanElementIndex", "planElementIndex")
      .flatMap {
        case n: java.lang.Number => Some(n.intValue())
        case s: CharSequence =>
          val str = s.toString.trim
          if (isNullOrNanString(str)) None
          else Try(str.toDouble.toInt).toOption
        case other => Try(other.toString.toDouble.toInt).toOption
      }
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
      x = readDouble(getFieldFromNames(record, "x", "activityLocationX")),
      y = readDouble(getFieldFromNames(record, "y", "activityLocationY")),
      departureTime = readDouble(getFieldFromNames(record, "departure_time", "departureTime", "activityEndTime")),
      expectedDurationMinutes = readDouble(getFieldOpt(record, "trip_dur_min")),
      expectedCostDollars = readDouble(getFieldOpt(record, "trip_cost_dollars")),
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
    val carsVal = Try(record.get("cars")).getOrElse(record.get("auto_ownership"))
    val cars = carsVal match {
      case n: java.lang.Number => n.intValue()
      case other               => Try(other.toString.toDouble.toInt).getOrElse(0)
    }
    val incomeVal = record.get("income")
    val income = incomeVal match {
      case n: java.lang.Number => n.intValue()
      case other               => Try(other.toString.toDouble.toInt).getOrElse(0)
    }
    val blockIdVal = record.get("block_id")
    val blockId = blockIdVal match {
      case n: java.lang.Number => n.longValue()
      case other               => Try(other.toString.toDouble.toLong).getOrElse(other.toString.toLong)
    }
    InputHousehold(
      householdId = record.get("household_id").toString.split("\\.")(0),
      cars = cars,
      income = income,
      blockId = blockId
    )
  }
}

class ParquetBlockReader(path: String) extends BaseParquetReader[Block](path) {

  override protected def transform(record: GenericRecord): Block = {
    val blockIdVal = record.get("block_id")
    val blockId = blockIdVal match {
      case n: java.lang.Number => n.longValue()
      case other               => Try(other.toString.toDouble.toLong).getOrElse(other.toString.toLong)
    }
    val x = record.get("x") match {
      case n: java.lang.Number => n.doubleValue()
      case other               => Try(other.toString.toDouble).getOrElse(0.0)
    }
    val y = record.get("y") match {
      case n: java.lang.Number => n.doubleValue()
      case other               => Try(other.toString.toDouble).getOrElse(0.0)
    }
    Block(
      blockId = blockId,
      x = x,
      y = y
    )
  }
}
