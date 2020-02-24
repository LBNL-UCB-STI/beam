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

  def getProbabilityList(keyValueTuple: (String, String)*): Array[String] = {
    getList(keyValueTuple: _*).map(_(RETURN_COLUMN))
  }

  private def sample(input: Array[Map[String, String]], sampleWithinRange: Boolean): Map[String, String] = {
    val pmf = input
      .map(
        value =>
          new CPair[Map[String, String], java.lang.Double](row(value, sampleWithinRange), value(RETURN_COLUMN).toDouble)
      )
      .toList
    val distr = new EnumeratedDistribution[Map[String, String]](rng, pmf.asJava)
    distr.sample()
  }

  def getSample(sampleWithinRange: Boolean, keyValueTuple: (String, String)*): Map[String, String] = {
    sample(getList(keyValueTuple: _*), sampleWithinRange)
  }

  def getRangeSample(
    sampleWithinRange: Boolean,
    keyValue: Map[String, String],
    keyRangeValueTuple: (String, CustomRange)*
  ): Map[String, String] = {
    sample(getRangeList(keyValue, keyRangeValueTuple: _*), sampleWithinRange)
  }

  def getProbability(keyValueTuple: (String, String)*): Double = {
    getProbabilityList(keyValueTuple: _*).map(_.toDouble).sum
  }

  private def getList(keyValueTuple: (String, String)*): Array[Map[String, String]] = {
    mappedArray._1
      .filter(
        map =>
          keyValueTuple
            .map(keyValue => {

              val value = map(keyValue._1)
              columnMapping.get(keyValue._1) match {
                case Some(v) if v == JointDistribution.RANGE_COLUMN_TYPE =>
                  val startEnd = toRange(value, true)
                  val range = toRange(keyValue._2)
                  range.start <= startEnd.start && range.end >= startEnd.end
                case _ =>
                  value == keyValue._2
              }

            })
            .reduce(_ && _)
      )
      .toArray
  }

  private def row(values: Map[String, String], range: Boolean): Map[String, String] = {
    if (range) {
      values.map {
        case (key, value) =>
          if (value.contains(",")) {
            val startEnd = toRange(value, true)
            key -> (startEnd.start + (startEnd.end - startEnd.start) * rng.nextDouble()).toString
          } else {
            key -> value
          }
      }
    } else {
      values
    }
  }

  def getRangeList(
    keyValueTuple: Map[String, String],
    keyRangeValueTuple: (String, CustomRange)*
  ): Array[Map[String, String]] = {

    mappedArray._1
      .filter(
        map =>
          keyRangeValueTuple
            .map(keyValue => {
              val value = map(keyValue._1)
              val startEnd = toRange(value, true)
              keyValue._2.start <= startEnd.start && keyValue._2.end >= startEnd.end
            })
            .reduce(_ && _)
          &&
          keyValueTuple
            .map {
              case (key, value) => map(key) == value
            }
            .reduce(_ && _)
      )
      .toArray
  }

  def getSum(keyValueTuple: (String, String)*): Double = {
    getProbabilityList(keyValueTuple: _*).map(_.toDouble).sum
  }

  private def toRange(value: String, trimBracket: Boolean = false): CustomRange = {
    val startLast = value.split(",")
    val start = startLast(0).trim
    val end = startLast(1).trim
    if (trimBracket)
      CustomRange(start.substring(1).toDouble, end.substring(0, end.length - 1).toDouble)
    else
      CustomRange(start.toDouble, end.toDouble)
  }

  private def toScalaMap(rec: JavaMap[String, String]): Map[String, String] = rec.asScala.toMap
}
