package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.entities._
import org.apache.avro.generic.GenericRecord
import org.apache.avro.AvroRuntimeException

import scala.util.Try

object ParquetReaders {

  def isNullOrNanString(str: String): Boolean =
    str.isEmpty || str.equalsIgnoreCase("nan") || str.equalsIgnoreCase("null")

  def parseLong(v: Any): Long = v match {
    case null                => 0L
    case n: java.lang.Number => if (n.doubleValue().isNaN) 0L else n.longValue()
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) 0L
      else Try(str.toDouble.toLong).getOrElse(Try(str.toLong).getOrElse(0L))
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) 0L
      else Try(str.toDouble.toLong).getOrElse(Try(str.toLong).getOrElse(0L))
  }

  def parseInt(v: Any): Int = v match {
    case null                => 0
    case n: java.lang.Number => if (n.doubleValue().isNaN) 0 else n.intValue()
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) 0
      else Try(str.toDouble.toInt).getOrElse(0)
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) 0
      else Try(str.toDouble.toInt).getOrElse(0)
  }

  def parseDouble(v: Any): Double = v match {
    case null                => 0.0
    case n: java.lang.Number => if (n.doubleValue().isNaN) 0.0 else n.doubleValue()
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) 0.0
      else Try(str.toDouble).getOrElse(0.0)
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) 0.0
      else Try(str.toDouble).getOrElse(0.0)
  }

  def readDoubleOpt(opt: Option[AnyRef]): Option[Double] = opt.flatMap {
    case null                => None
    case n: java.lang.Number => if (n.doubleValue().isNaN) None else Some(n.doubleValue())
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) None
      else Try(str.toDouble).toOption
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) None
      else Try(str.toDouble).toOption
  }

  def parsePlanIndex(v: Any): Option[Int] = v match {
    case null => None
    case n: java.lang.Number =>
      if (n.doubleValue().isNaN) None
      else Some(n.intValue())
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) None
      else Try(str.toDouble.toInt).toOption
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) None
      else Try(str.toDouble.toInt).toOption
  }

  def parsePlanScore(v: Any): Option[Double] = v match {
    case null => None
    case n: java.lang.Number =>
      if (n.doubleValue().isNaN) Some(Double.NaN)
      else Some(n.doubleValue())
    case s: CharSequence =>
      val str = s.toString.trim
      if (str.isEmpty || str.equalsIgnoreCase("null")) None
      else if (str.equalsIgnoreCase("nan")) Some(Double.NaN)
      else Try(str.toDouble).toOption
    case other =>
      val str = other.toString.trim
      if (str.isEmpty || str.equalsIgnoreCase("null")) None
      else if (str.equalsIgnoreCase("nan")) Some(Double.NaN)
      else Try(str.toDouble).toOption
  }

  def parsePlanSelected(v: Any): Option[Boolean] = v match {
    case null                 => None
    case b: java.lang.Boolean => Some(b.booleanValue())
    case n: java.lang.Number =>
      if (n.doubleValue().isNaN) None
      else Some(n.intValue() != 0)
    case s: CharSequence =>
      val str = s.toString.trim
      if (isNullOrNanString(str)) None
      else InputPlanElement.parseBoolean(str)
    case other =>
      val str = other.toString.trim
      if (isNullOrNanString(str)) None
      else InputPlanElement.parseBoolean(str)
  }
}

class ParquetPersonReader(path: String) extends BaseParquetReader[InputPersonInfo](path) {
  import ParquetReaders._

  override protected def transform(record: GenericRecord): InputPersonInfo = {
    val industryField =
      try {
        Option(record.get("industry")).map(_.toString)
      } catch {
        case _: AvroRuntimeException => None // Field doesn't exist in schema
      }

    val age = parseInt(record.get("age"))
    val sex = Sex.determineSex(parseInt(record.get("sex")))
    val valueOfTime = readDoubleOpt(Try(Option(record.get("value_of_time"))).toOption.flatten)

    val personId = Option(record.get("person_id"))
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing person_id in record: $record"))
    val householdId = Option(record.get("household_id"))
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing household_id in record: $record"))

    InputPersonInfo(
      personId = personId,
      householdId = householdId,
      age = age,
      sex = sex,
      industry = industryField,
      valueOfTime = valueOfTime
    )
  }
}

class ParquetPlanReader(path: String) extends BaseParquetReader[InputPlanElement](path) {
  import ParquetReaders._

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

  private[reader] def readPlanIndex(record: GenericRecord): Option[Int] = {
    getFieldFromNames(record, "planIndex", "planindex").flatMap(parsePlanIndex)
  }

  private[reader] def readPlanScore(record: GenericRecord): Option[Double] = {
    getFieldFromNames(record, "planScore", "planscore").flatMap(parsePlanScore)
  }

  private[reader] def readPlanSelected(record: GenericRecord): Option[Boolean] = {
    getFieldFromNames(record, "planSelected", "planselected").flatMap(parsePlanSelected)
  }

  override protected def transform(record: GenericRecord): InputPlanElement = {
    val personId = getFieldFromNames(record, "person_id", "personId")
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing person_id in parquet record: $record"))
    val planElementIndex = getFieldFromNames(record, "PlanElementIndex", "planElementIndex")
      .flatMap {
        case null => None
        case n: java.lang.Number =>
          if (n.doubleValue().isNaN) None
          else Some(n.intValue())
        case s: CharSequence =>
          val str = s.toString.trim
          if (isNullOrNanString(str)) None
          else Try(str.toDouble.toInt).toOption
        case other =>
          val str = other.toString.trim
          if (isNullOrNanString(str)) None
          else Try(str.toDouble.toInt).toOption
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
      x = readDoubleOpt(getFieldFromNames(record, "x", "activityLocationX")),
      y = readDoubleOpt(getFieldFromNames(record, "y", "activityLocationY")),
      departureTime = readDoubleOpt(getFieldFromNames(record, "departure_time", "departureTime"))
        .orElse(readDoubleOpt(getFieldOpt(record, "activityEndTime")).map(_ / 3600.0)),
      expectedDurationMinutes = readDoubleOpt(getFieldOpt(record, "trip_dur_min")),
      expectedCostDollars = readDoubleOpt(getFieldOpt(record, "trip_cost_dollars")),
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
  import ParquetReaders._

  override protected def transform(record: GenericRecord): InputHousehold = {
    val carsVal = Try(Option(record.get("cars"))).toOption.flatten.getOrElse(record.get("auto_ownership"))
    val cars = parseInt(carsVal)
    val income = parseInt(record.get("income"))
    val blockId = parseLong(record.get("block_id"))
    val householdId = Option(record.get("household_id"))
      .map(_.toString.split("\\.")(0))
      .getOrElse(throw new IllegalArgumentException(s"Missing household_id in record: $record"))

    InputHousehold(
      householdId = householdId,
      cars = cars,
      income = income,
      blockId = blockId
    )
  }
}

class ParquetBlockReader(path: String) extends BaseParquetReader[Block](path) {
  import ParquetReaders._

  override protected def transform(record: GenericRecord): Block = {
    val blockId = parseLong(record.get("block_id"))
    val x = parseDouble(record.get("x"))
    val y = parseDouble(record.get("y"))
    Block(
      blockId = blockId,
      x = x,
      y = y
    )
  }
}
