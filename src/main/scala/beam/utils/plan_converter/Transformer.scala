package beam.utils.plan_converter

trait Transformer[T] {

  def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }

  def transform(m: java.util.Map[String, String]): T
}
