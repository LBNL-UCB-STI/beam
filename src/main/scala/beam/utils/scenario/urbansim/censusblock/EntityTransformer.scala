package beam.utils.scenario.urbansim.censusblock

import com.univocity.parsers.common.record.Record

/*
 Do NOT make the get methods generic as the memory cost will be higher. A method per type is the cost of higher performance
 */
trait EntityTransformer[T] {

  def getStringIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): String = {
    val firstValue = rec.getString(columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(rec.getString).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getDoubleIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Double = {
    val firstValue = rec.getDouble(columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(rec.getDouble).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getLongIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Long = {
    val firstValue = rec.getLong(columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(rec.getLong).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getIntIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Int = {
    val firstValue = rec.getInt(columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(rec.getInt).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getFloatIfNotNull(
                       rec: Record,
                       columnName: String,
                       maybeSecondColumnName: Option[String] = None
                     ): Float = {
    val firstValue = rec.getFloat(columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(rec.getFloat).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getStringOptional(rec: Record, column: String): Option[String] = Option(rec.getString(column))

  def getDoubleOptional(rec: Record, column: String): Option[Double] = Option(rec.getDouble(column))

  def transform(rec: Record): T
}
