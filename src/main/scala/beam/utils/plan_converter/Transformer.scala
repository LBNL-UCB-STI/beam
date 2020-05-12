package beam.utils.plan_converter

trait Transformer[T] {

  def getIfNotNull(rec: java.util.Map[String, String], column: String): String =
    getOptional(rec, column).getOrElse(
      throw new java.lang.AssertionError(s"Assertion failed: Value in column '$column' is null")
    )

  def getOptional(rec: java.util.Map[String, String], column: String): Option[String] = Option(rec.get(column))

  def transform(m: java.util.Map[String, String]): T
}
