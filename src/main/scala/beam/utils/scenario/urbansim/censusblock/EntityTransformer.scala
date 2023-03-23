package beam.utils.scenario.urbansim.censusblock

import com.univocity.parsers.common.record.{Record, RecordMetaData}

import scala.collection.mutable

/*
 Do NOT make the get methods generic as the memory cost will be higher. A method per type is the cost of higher performance
 */
trait EntityTransformer[T] {

  val columnNameToIndex = mutable.Map.empty[String, Int]

  def getColumnIndexBy(columnName: String, recordMetaData: RecordMetaData) = {
    columnNameToIndex.getOrElseUpdate(columnName, recordMetaData.indexOf(columnName))
  }

  private def getString(rec: Record, columnName: String): String = {
    val index = getColumnIndexBy(columnName, rec.getMetaData)
    if (index >= 0) rec.getString(index)
    else null
  }

  def getStringIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): String = {
      val firstValue = getString(rec, columnName)
      if(firstValue != null) firstValue
      else {
        maybeSecondColumnName.map(getString(rec, _)).getOrElse(
          throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
      }
  }

  private def getDouble(rec: Record, columnName: String): Double = {
    val index = getColumnIndexBy(columnName, rec.getMetaData)
    if (index >= 0) rec.getDouble(index)
    else null
  }

  def getDoubleIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Double = {
    val firstValue = getDouble(rec, columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(getDouble(rec, _)).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  private def getLong(rec: Record, columnName: String): Long = {
    val index = getColumnIndexBy(columnName, rec.getMetaData)
    if (index >= 0) rec.getLong(index)
    else null
  }

  def getLongIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Long = {
    val firstValue = getLong(rec, columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(getLong(rec, _)).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  private def getInt(rec: Record, columnName: String): Int = {
    val index = getColumnIndexBy(columnName, rec.getMetaData)
    if (index >= 0) rec.getInt(index)
    else null
  }

  def getIntIfNotNull(
    rec: Record,
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): Int = {
    val firstValue = getInt(rec, columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(getInt(rec, _)).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  private def getFloat(rec: Record, columnName: String): Float = {
    val index = getColumnIndexBy(columnName, rec.getMetaData)
    if (index >= 0) rec.getFloat(index)
    else null
  }

  def getFloatIfNotNull(
                       rec: Record,
                       columnName: String,
                       maybeSecondColumnName: Option[String] = None
                     ): Float = {
    val firstValue = getFloat(rec, columnName)
    if(firstValue != null) firstValue
    else {
      maybeSecondColumnName.map(getFloat(rec, _)).getOrElse(
        throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' and '$maybeSecondColumnName' is null"))
    }
  }

  def getStringOptional(rec: Record, column: String): Option[String] = Option(getString(rec, column))

  def getDoubleOptional(rec: Record, column: String): Option[Double] = Option(getDouble(rec, column))

  def transform(rec: Record): T
}
