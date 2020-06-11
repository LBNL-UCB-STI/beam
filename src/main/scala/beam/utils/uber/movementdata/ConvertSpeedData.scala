package beam.utils.uber.movementdata

import java.util.concurrent.TimeUnit
import java.{lang, util}

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.uber.movementdata.SpeedReader.SpeedData
import com.conveyal.osmlib.{Node, OSM}
import com.vividsolutions.jts.geom.Envelope

import scala.util.Random

class ConvertSpeedData(
  val pathToSpeedCsv: String,
  val pathToOsm: String,
  val boundingBox: Envelope,
  val samplePerHour: Int,
  val seed: Int
) {
  import ConvertSpeedData._

  private val osm: OSM = ProfilingUtils.timed("Read osm file", x => println(x)) {
    val temp = new OSM(null)
    temp.readFromFile(pathToOsm)
    temp
  }
  private val osmNodes: util.Map[lang.Long, Node] = osm.nodes

  def write(): Unit = {
    val sr = new SpeedReader(pathToSpeedCsv)
    val (speeds, toClose) = sr.read(speedData => {
      val startAndEndNodeExistInOSM = Option(osmNodes.get(speedData.startNodeId)).nonEmpty && Option(
        osmNodes.get(speedData.endNodeId)
      ).nonEmpty
      val isRightDate = speedData.year == 2019 && speedData.month == 10 && speedData.day == 16
      startAndEndNodeExistInOSM && isRightDate
    })
    try {
      val matSpeeds = ProfilingUtils.timed("Read all the speeds", x => println(x)) {
        speeds.toArray
      }
      println(s"matSpeeds: ${matSpeeds.length}")
      val speedsWithinBoundingBox = matSpeeds.collect {
        case speed if withinBoundingBox(speed) =>
          speed
      }
      writeToCsv(speedsWithinBoundingBox, "SanFrancisco-Full_speeds.csv")
      println(s"speedsWithinBoundingBox: ${speedsWithinBoundingBox.length}")

      val rnd = new Random(seed)
      val sampled = speedsWithinBoundingBox
        .groupBy(x => x.hour)
        .flatMap {
          case (_, xs) =>
            rnd.shuffle(xs.toList).take(samplePerHour)
        }
        .toArray
        .sortBy(x => x.hour)
      println(s"sampled: ${sampled.length}")

      writeToCsv(sampled, s"SanFrancisco-Sampled_speeds_${samplePerHour}.csv")
    } finally {
      toClose.close()
    }
  }

  private def writeToCsv(speedsWitinBoundingBox: Iterable[SpeedData], filePath: String): Unit = {
    val headers = Array(
      "year",
      "month",
      "day",
      "hour",
      "osm_way_id",
      "osm_start_node_id",
      "osm_end_node_id",
      "speed_ms_mean",
      "speed_ms_stddev",
      "start_lat",
      "start_lon",
      "end_lat",
      "end_lon",
      "link"
    )
    val cswWriter = new CsvWriter(filePath, headers)
    speedsWitinBoundingBox.foreach { speed =>
      val startNode = osmNodes.get(speed.startNodeId)
      val endNode = osmNodes.get(speed.endNodeId)
      val startCoord = WgsCoordinate(startNode.getLat, startNode.getLon)
      val endCoord = WgsCoordinate(endNode.getLat, endNode.getLon)
      val googleLink = getLink(startCoord, endCoord, speed.hour)
      cswWriter.write(
        speed.year,
        speed.month,
        speed.day,
        speed.hour,
        speed.wayId,
        speed.startNodeId,
        speed.endNodeId,
        speed.meanSpeedInMetersPerSecond,
        speed.stdDevSpeed,
        startCoord.latitude,
        startCoord.longitude,
        endCoord.latitude,
        endCoord.longitude,
        googleLink
      )
    }
    cswWriter.close()
  }

  private def withinBoundingBox(speedData: SpeedData): Boolean = {
    val startNode = osmNodes.get(speedData.startNodeId)
    require(startNode != null, s"NodeId[startNodeId] ${speedData.startNodeId} does not exist in OSM")
    val endNode = osmNodes.get(speedData.endNodeId)
    require(endNode != null, s"NodeId[endNodeId] ${speedData.endNodeId} does not exist in OSM")
    boundingBox.contains(startNode.getLat, startNode.getLon) && boundingBox.contains(endNode.getLat, endNode.getLon)
  }
}

object ConvertSpeedData {

  def getLink(startCoord: WgsCoordinate, endCoord: WgsCoordinate, hour: Byte): String = {
    val mainPath =
      s"https://www.google.com/maps/dir/${startCoord.latitude}%09${startCoord.longitude}/${endCoord.latitude}%09${endCoord.longitude}"
    // We have chosen 2019-10-16 (Wednesday) as a day to get the routes from Google. It is 1571184000 in Unix time
    val startOfTheDay: Long = 1571184000L
    val timeEpochSeconds = startOfTheDay + TimeUnit.HOURS.toSeconds(hour)
    val ending =
      s"/data=!3m1!4b1!4m15!4m14!1m3!2m2!1d-97.7584165!2d30.3694661!1m3!2m2!1d-97.7295503!2d30.329807!2m4!2b1!6e0!7e2!8j${timeEpochSeconds}!3e0"
    mainPath + ending
  }

  def main(args: Array[String]): Unit = {
    val pathToSpeedCsv: String = "D:/Work/beam/Uber/SpeedData/movement-speeds-hourly-san-francisco-2019-10.csv"
    val pathToOsm: String = "D:/Work/beam/Uber/SpeedData/norcal-latest.osm.pbf"

    // Lat1, Lat2, Long1, Long2
    // https://www.google.com/maps/d/drive?state=%7B%22ids%22%3A%5B%221JxxEvTayxiqi_GXe5-pzmNeV72wmjkkz%22%5D%2C%22action%22%3A%22open%22%2C%22userId%22%3A%22111892074776602978600%22%7D&usp=sharing
    val boundingBox: Envelope = new Envelope(37.616703303, 37.887535417, -122.672606043, -122.186313573)
    val stuff = new ConvertSpeedData(pathToSpeedCsv, pathToOsm, boundingBox, samplePerHour = 100, seed = 42)
    stuff.write()
  }

}
