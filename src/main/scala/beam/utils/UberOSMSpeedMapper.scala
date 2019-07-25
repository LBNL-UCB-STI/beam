package beam.utils
import beam.analysis.via.CSVWriter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

object UberOSMSpeedMapper extends LazyLogging{

  /*
    We need to pass 4 argument
    1. network file path
    2. comma separate list of movement-segment file example
    3. comma separate list of movement-speed files
    4. output file name
   */

  def main(args: Array[String]): Unit = {

    val networkFile = args(0)
    val movementFiles = args(1)
    val speedFiles = args(2)
    val outputFile = args(3)

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(networkFile)
    writeOsmMappingCsv(network, movementFiles , speedFiles , outputFile)
  }

  def writeOsmMappingCsv(network: Network , movementSegmentFile: String, speedFile: String, outputFile: String): Unit ={
    val osmSegment = readOsmWayToSegmentFile(movementSegmentFile)
    val osmSpeed = readOsmSpeed(speedFile)

    val csvWriter = new CSVWriter(outputFile)
    val writer = csvWriter.getBufferedWriter
    writer.write("linkid,capacity,free_speed,length")

    network.getLinks.forEach((linkId,link) => {
      val attributes = link.getAttributes
      val origid = attributes.getAttribute("origid")
      if(origid != null) {
        osmSegment.get(origid.toString).foreach(segment =>{
          val speed = osmSpeed.get(segment)
          speed.foreach(s => {
            writer.newLine()
            writer.write(s"${linkId.toString},,${s*0.44704},")
          })
        })
      }
    })

    writer.close()
  }


  def readOsmWayToSegmentFile(paths: String): Map[String, String] = {
    paths.split(",").flatMap(readAs[(String, String)](_, "osmway to segment", toOsmSegmentMap)).toMap
  }

  def readOsmSpeed(paths: String): Map[String, Double] = {
    paths.split(",").flatMap(readAs[(String, Double)](_, "osm speed", toSpeed)).groupBy(_._1).map({
      case(key, values) => key -> values.map(_._2).max
    })
  }

  private def toOsmSegmentMap(rec: java.util.Map[String, String]): (String, String) = {
    getIfNotNull(rec, "osm_way_id") -> getIfNotNull(rec, "segment_id")
  }

  private def toSpeed(rec: java.util.Map[String, String]): (String, Double) = {
    (getIfNotNull(rec, "segment_id"), getIfNotNull(rec, "speed_mph_mean").toDouble)
  }


  private[utils] def readAs[T](path: String, what: String, mapper: java.util.Map[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => logger.info(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}
