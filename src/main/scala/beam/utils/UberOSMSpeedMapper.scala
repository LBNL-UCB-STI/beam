package beam.utils

import beam.analysis.via.CSVWriter
import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

object UberOSMSpeedMapper extends GenericCsvReader with LazyLogging {

  /*
    We need to pass 4 argument
    1. network file path
    2. comma separate list of movement-segment file example
    3. comma separate list of movement-speed files
    4. output file name
   */

  private val meterPerSec = 0.44704 //1 milesPerHour = 0.44704 meterPerSec

  def main(args: Array[String]): Unit = {

    val networkFile = args(0)
    val movementFiles = args(1)
    val speedFiles = args(2)
    val outputFile = args(3)

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(networkFile)
    writeOsmMappingCsv(network, movementFiles, speedFiles, outputFile)
  }

  def writeOsmMappingCsv(network: Network, movementSegmentFile: String, speedFile: String, outputFile: String): Unit = {
    val osmSegment = readOsmWayToSegmentFile(movementSegmentFile)
    val osmSpeed = readOsmSpeed(speedFile)

    val csvWriter = new CSVWriter(outputFile)
    val writer = csvWriter.getBufferedWriter
    writer.write("linkid,capacity,free_speed,length")

    network.getLinks.forEach((linkId, link) => {
      val attributes = link.getAttributes
      val origid = attributes.getAttribute("origid")
      if (origid != null) {
        osmSegment
          .get(origid.toString)
          .foreach(segment => {
            val speed = osmSpeed.get(segment)
            speed.foreach(s => {
              writer.newLine()
              writer.write(s"${linkId.toString},,${s * meterPerSec},")
            })
          })
      }
    })

    writer.close()
  }

  def readOsmWayToSegmentFile(paths: String): Map[String, String] = {
    paths.split(",").flatMap(readAs[(String, String)](_, toOsmSegmentMap, _ => true)._1).toMap
  }

  def readOsmSpeed(paths: String): Map[String, Double] = {
    paths
      .split(",")
      .flatMap(readAs[(String, Double)](_, toSpeed, _ => true)._1)
      .groupBy(_._1)
      .map({ case (key, values) =>
        key -> values.map(_._2).max
      })
  }

  private def toOsmSegmentMap(rec: java.util.Map[String, String]): (String, String) = {
    getIfNotNull(rec, "osm_way_id") -> getIfNotNull(rec, "segment_id")
  }

  private def toSpeed(rec: java.util.Map[String, String]): (String, Double) = {
    (getIfNotNull(rec, "segment_id"), getIfNotNull(rec, "speed_mph_mean").toDouble)
  }
}
