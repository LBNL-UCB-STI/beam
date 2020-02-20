package beam.utils.data.ctpp

import java.util
import java.util.{Map => JavaMap}

import beam.utils.data.ctpp.JointDistribution.{columnTypes, mappedArray, readAs, toMap, CustomRange, RETURN_COLUMN}
import beam.utils.{FileUtils, ProfilingUtils}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.Random
import scala.collection.JavaConverters._

object JointDistribution {

  case class CustomRange(start: Double, end: Double)

  var columnTypes: Map[String, String] = _
  var mappedArray: Array[util.Map[String, String]] = _

  val RANGE_COLUMN_TYPE = "range"
  val DOUBLE_COLUMN_TYPE = "double"
  val INT_COLUMN_TYPE = "int"
  val STRING_COLUMN_TYPE = "string"
  val RETURN_COLUMN = "probability"

  private[ctpp] def readAs[T](path: String, what: String, mapper: JavaMap[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => println(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private def toMap(rec: JavaMap[String, String]): JavaMap[String, String] = {
    val map = new util.HashMap[String, String] {}
    rec
      .keySet()
      .forEach(key => {
        map.put(key, getIfNotNull(rec, key))
      })
    map
  }

  private def getIfNotNull(rec: JavaMap[String, String], column: String, default: String = null): String = {
    val v = rec.getOrDefault(column, default)
    assert(v != null, s"Value in column '$column' is null")
    v
  }

}
case class JointDistribution(fileName: String, columnMapping: Map[String, String] = Map()) {

  mappedArray = readAs(fileName, "ReadFile", toMap)
  columnTypes = columnMapping
  val random = new Random()

  def getProbabilityList(keyValueTuple: (String, String)*): Array[String] = {
    getList(keyValueTuple: _*).map(_.get(RETURN_COLUMN))
  }

  def getSample(sampleWithinRange: Boolean, keyValueTuple: (String, String)*): Map[String, String] = {
    Distribution(
      getList(keyValueTuple: _*)
        .map(value => {
          row(value.asScala.toMap, sampleWithinRange) -> value.get(RETURN_COLUMN).toDouble
        })
        .toMap
    ).sample
  }

  def getRangeSample(
    sampleWithinRange: Boolean,
    keyValue: Map[String, String],
    keyRangeValueTuple: (String, CustomRange)*
  ): Map[String, String] = {

    Distribution(
      getRangeList(keyValue, keyRangeValueTuple: _*)
        .map(value => {
          row(value.asScala.toMap, sampleWithinRange) -> value.get(RETURN_COLUMN).toDouble
        })
        .toMap
    ).sample
  }

  def getProbability(keyValueTuple: (String, String)*): Double = {
    getProbabilityList(keyValueTuple: _*).map(_.toDouble).sum
  }

  private def getList(keyValueTuple: (String, String)*): Array[util.Map[String, String]] = {
    mappedArray.filter(
      map =>
        keyValueTuple
          .map(keyValue => {

            val value = map.get(keyValue._1)
            columnTypes.get(keyValue._1) match {
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
  }

  private def row(values: Map[String, String], range: Boolean): Map[String, String] = {
    if (range) {
      values.map {
        case (key, value) =>
          if (value.contains(",")) {
            val startEnd = toRange(value, true)
            key -> (startEnd.start + (startEnd.end - startEnd.start) * random.nextDouble()).toString
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
  ): Array[util.Map[String, String]] = {

    mappedArray.filter(
      map =>
        keyRangeValueTuple
          .map(keyValue => {
            val value = map.get(keyValue._1)
            val startEnd = toRange(value, true)
            keyValue._2.start <= startEnd.start && keyValue._2.end >= startEnd.end
          })
          .reduce(_ && _)
        &&
        keyValueTuple
          .map {
            case (key, value) => map.get(key) == value
          }
          .reduce(_ && _)
    )
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
}

case class Distribution[T](probs: Map[T, Double]) {
  private val probability = new ListBuffer[Double]
  private val events = new ListBuffer[T]
  private var sumProb = 0.0
  private val random = new Random()
  probs.map {
    case (row, value) =>
      sumProb += value
      events += row
      probability += value
  }

  def sample: T = {
    var prob = random.nextDouble() * sumProb
    var i = 0
    while (prob > 0) {
      i = i + 1
      prob = prob - probability(i)
    }
    events(i - 1)
  }
}
