package beam.router

import beam.utils.csv.GenericCsvReader
import beam.utils.{MathUtils, TravelTimeCalculatorHelper}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils.isBlank
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetReader
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.hadoop.util.HadoopInputFile

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

class LinkTravelTimeContainer(fileName: String, timeBinSizeInSeconds: Int, maxHour: Int)
    extends BeamTravelTime
    with LazyLogging {

  private val travelTimeCalculator: BeamTravelTime =
    TravelTimeCalculatorHelper.CreateTravelTimeCalculator(timeBinSizeInSeconds, loadLinkStats().asJava)

  private def loadLinkStats(): scala.collection.Map[String, Array[Double]] = {
    val start = System.currentTimeMillis()
    val linkTravelTimeMap: mutable.HashMap[String, Array[Double]] = mutable.HashMap()
    logger.info(s"Stats fileName [$fileName] is being loaded")

    if (fileName.endsWith(".parquet")) {
      val conf = new Configuration()
      val inputFile = HadoopInputFile.fromPath(new Path(fileName), conf)
      val reader = AvroParquetReader.builder[GenericRecord](inputFile).withConf(conf).build()
      try {
        var record = reader.read()
        while (record != null) {
          val linkId = record.get("link").toString
          val hour = record.get("hour").asInstanceOf[Int]
          val travelTime = record.get("traveltime").asInstanceOf[Double]
          val stat = record.get("stat").toString
          if (!isBlank(linkId) && stat.equalsIgnoreCase("avg")) {
            val travelTimePerHourArr = linkTravelTimeMap.getOrElseUpdate(linkId, Array.ofDim[Double](maxHour))
            travelTimePerHourArr.update(hour, travelTime)
          }
          record = reader.read()
        }
      } finally {
        Try(reader.close())
      }
    } else {
      val (iterator, closable) =
        GenericCsvReader.readAs(fileName, mapper, (row: (String, Int, Double)) => !isBlank(row._1))
      try {
        iterator.foreach { case (linkId, hour, travelTime) =>
          val travelTimePerHourArr = linkTravelTimeMap.getOrElseUpdate(linkId, Array.ofDim[Double](maxHour))
          travelTimePerHourArr.update(hour, travelTime)
        }
      } finally {
        Try(closable.close())
      }
    }

    val end = System.currentTimeMillis()
    logger.info("LinkTravelTimeMap is initialized in {} ms", end - start)

    linkTravelTimeMap
  }

  def mapper(row: java.util.Map[String, String]): (String, Int, Double) = {
    val linkId = row.get("link")
    val stat = row.get("stat")
    if (isBlank(linkId) || isBlank(stat) || !stat.equalsIgnoreCase("avg")) {
      ("", 0, 0.0)
    } else {
      val hour = MathUtils.doubleToInt(row.get("hour").toDouble)
      val travelTime = row.get("traveltime").toDouble
      (linkId, hour, travelTime)
    }
  }

  def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double = {
    travelTimeCalculator.getLinkTravelTime(link, time, person, vehicle)
  }

  /**
    * Get travel time using integer link ID directly.
    * This avoids the overhead of Link object lookups and string parsing.
    */
  def getLinkTravelTime(linkId: Int, time: Double): Double = {
    travelTimeCalculator.getLinkTravelTime(linkId, time)
  }
}
