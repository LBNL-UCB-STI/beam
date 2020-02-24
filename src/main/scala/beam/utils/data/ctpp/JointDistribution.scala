package beam.utils.data.ctpp

import java.util.{Map => JavaMap}
import org.apache.commons.math3.util.{Pair => CPair}

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.JointDistribution.{readAs, CustomRange, RETURN_COLUMN}
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister

import scala.collection.JavaConverters._

object JointDistribution extends GenericCsvReader {

  case class CustomRange(start: Double, end: Double)

  val RANGE_COLUMN_TYPE = "range"
  val DOUBLE_COLUMN_TYPE = "double"
  val INT_COLUMN_TYPE = "int"
  val STRING_COLUMN_TYPE = "string"
  val RETURN_COLUMN = "probability"

}

case class JointDistribution(fileName: String, columnMapping: Map[String, String] = Map()) {

  val rng: MersenneTwister = new MersenneTwister()
  val mappedArray = readAs[Map[String, String]](fileName, toScalaMap, _ => true)

  def getProbabilityList(keyValueTuple: (String, Either[String, CustomRange])*): Array[String] = {
    getRangeList(keyValueTuple: _*).map(_(RETURN_COLUMN))
  }

  def getSample(
    sampleWithinRange: Boolean,
    keyValueTuple: (String, Either[String, CustomRange])*
  ): Map[String, String] = {
    val pmf = getRangeList(keyValueTuple: _*)
      .map(
        value =>
          new CPair[Map[String, String], java.lang.Double](row(value, sampleWithinRange), value(RETURN_COLUMN).toDouble)
      )
      .toList
    val distr = new EnumeratedDistribution[Map[String, String]](rng, pmf.asJava)
    distr.sample()
  }

  def getProbability(keyValueTuple: (String, Either[String, CustomRange])*): Double = {
    getProbabilityList(keyValueTuple: _*).map(_.toDouble).sum
  }

  private def row(values: Map[String, String], range: Boolean): Map[String, String] = {
    if (range) {
      values.map {
        case (key, value) =>
          if (value.contains(",")) {
            val startEnd = toRange(Left(value), true)
            key -> (startEnd.start + (startEnd.end - startEnd.start) * rng.nextDouble()).toString
          } else {
            key -> value
          }
      }
    } else {
      values
    }
  }

  def getRangeList(keyValueTuple: (String, Either[String, CustomRange])*): Array[Map[String, String]] = {
    mappedArray._1
      .filter(
        map =>
          keyValueTuple
            .map(keyValue => {

              val value = map(keyValue._1)
              columnMapping.get(keyValue._1) match {
                case Some(v) if v == JointDistribution.RANGE_COLUMN_TYPE =>
                  val startEnd = toRange(Left(value), true)
                  val range = toRange(keyValue._2)
                  range.start <= startEnd.start && range.end >= startEnd.end
                case _ =>
                  keyValue._2 match {
                    case Left(left) => value == left
                    case _          => false
                  }
              }
            })
            .reduce(_ && _)
      )
      .toArray
  }

  private def toRange(range: Either[String, CustomRange], trimBracket: Boolean = false): CustomRange = {
    range match {
      case Left(value) =>
        val startLast = value.split(",")
        val start = startLast(0).trim
        val end = startLast(1).trim
        if (trimBracket)
          CustomRange(start.substring(1).toDouble, end.substring(0, end.length - 1).toDouble)
        else
          CustomRange(start.toDouble, end.toDouble)
      case Right(value) => value
    }
  }

  private def toScalaMap(rec: JavaMap[String, String]): Map[String, String] = rec.asScala.toMap
}
