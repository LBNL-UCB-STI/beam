package beam.physsim.jdeqsim

import java.io.BufferedInputStream
import java.util
import java.util.Map

import beam.utils.FileUtils.using
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import org.apache.commons.io.FilenameUtils

import scala.collection.JavaConverters._

/**
  *
  * @author Dmitry Openkov
  */
object LinkTravelTimeExtractor extends App {

  if (args.length != 2) {
    println("Usage: LinkTravelTimeExtractor path/to/transcome_mapping.csv.gz path/to/travel_time_map.bin")
    System.exit(1)
  }

  val bl = readBeamLinks(args(0))
  val map = deserialize(args(1))
  writeLinkData(FilenameUtils.removeExtension(args(1)) + ".csv.gz", map, bl)

  private def writeLinkData(fileName: String, map: util.Map[String, Array[Double]], beamLinks: Set[String]): Unit = {
    using(new CsvWriter(fileName, Array("link_id", "hour", "travel_time"))) { writer =>
      for {
        entry <- map.entrySet().iterator().asScala if beamLinks.contains(entry.getKey)
        linkId = entry.getKey
        ttByHour = entry.getValue
        (tt, hour) <- ttByHour.zipWithIndex
      } {
        writer.writeRow(IndexedSeq(linkId, hour, tt))
      }
    }
  }

  private def readBeamLinks(path: String): Set[String] = {
    val (rdr, toClose) = GenericCsvReader.readAs[String](path, mapper => mapper.get("beamLink"), _ => true)
    val beamLinks = rdr.toSet
    toClose.close()
    println(beamLinks.size)
    beamLinks
  }

  private def deserialize(path: String): util.Map[String, Array[Double]] = {
    import java.io.FileInputStream
    import java.io.ObjectInputStream
    import java.util
    val map: util.Map[String, Array[Double]] =
      using(new ObjectInputStream(new BufferedInputStream(new FileInputStream(path)))) { ois =>
        ois.readObject.asInstanceOf[util.Map[String, Array[Double]]]
      }
    println(map.size())
    map
  }
}
