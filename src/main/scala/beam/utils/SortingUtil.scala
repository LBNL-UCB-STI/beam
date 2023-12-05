package beam.utils

import scala.util.Try

/**
  * @author Dmitry Openkov
  */
object SortingUtil {

  def sortAsIntegers(seq: Seq[String]): Option[Seq[String]] =
    Try(seq.sortBy(_.toLong)).toOption
}
