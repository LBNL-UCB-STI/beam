package beam.utils

import scala.util.Try

/**
  * @author Dmitry Openkov
  */
object SortingUtil {

  def sortAsIntegers(seq: Seq[String]): Option[Seq[String]] =
    Try(seq.zip(seq.map(_.toInt)).sortBy(_._2).map(_._1)).toOption
}
