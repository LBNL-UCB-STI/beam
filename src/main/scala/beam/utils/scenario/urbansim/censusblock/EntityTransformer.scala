package beam.utils.scenario.urbansim.censusblock

import java.io

trait EntityTransformer[T] {

  def getIfNotNull(
    rec: java.util.Map[String, String],
    columnName: String,
    maybeSecondColumnName: Option[String] = None
  ): String = {
    val maybeValueFirstTry: Option[String] = getOptional(rec, columnName)
    val maybeValue = if (maybeValueFirstTry.isEmpty && maybeSecondColumnName.nonEmpty) {
      getOptional(rec, maybeSecondColumnName.get)
    } else {
      maybeValueFirstTry
    }

    maybeValue.getOrElse(
      maybeSecondColumnName match {
        case Some(secondColumnName) =>
          throw new java.lang.AssertionError(
            s"Assertion failed: Value in columns '$columnName' and '$secondColumnName' is null"
          )
        case _ => throw new java.lang.AssertionError(s"Assertion failed: Value in column '$columnName' is null")
      }
    )
  }

  def getOptional(rec: java.util.Map[String, String], column: String): Option[String] = Option(rec.get(column))

  def transform(rec: java.util.Map[String, String]): T
}
