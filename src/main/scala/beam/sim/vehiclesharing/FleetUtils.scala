package beam.sim.vehiclesharing

import java.io.FileWriter
import java.util

import beam.agentsim.infrastructure.taz.TAZ
import beam.utils.FileUtils
import org.apache.log4j.Logger
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.io.{CsvMapReader, CsvMapWriter}
import org.supercsv.prefs.CsvPreference

object FleetUtils {

  private val logger = Logger.getLogger("FleetUtils")
  private val fileHeader = Seq[String]("taz", "x", "y", "fleetShare")

  def readCSV(filePath: String): Vector[(Id[TAZ], Coord, Double)] = {
    var res = Vector.empty[(Id[TAZ], Coord, Double)]
    var mapReader: CsvMapReader = null
    try {
      mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val idz = line.getOrDefault(fileHeader(0), "")
        val x = line.getOrDefault(fileHeader(1), "0.0").toDouble
        val y = line.getOrDefault(fileHeader(2), "0.0").toDouble
        val vehShare = line.get(fileHeader(3)).toDouble
        res = res :+ (Id.create(idz, classOf[TAZ]), new Coord(x, y), vehShare)
        line = mapReader.read(header: _*)
      }
    } catch {
      case e: Exception => logger.info(s"issue with reading $filePath: $e")
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res
  }

  def writeCSV(filePath: String, data: Vector[(Id[TAZ], Coord, Double)]): Unit = {
    FileUtils.using(new CsvMapWriter(new FileWriter(filePath), CsvPreference.STANDARD_PREFERENCE)) { writer =>
      writer.writeHeader(fileHeader: _*)
      val rows = data.map { row =>
        val rowMap = new util.HashMap[String, Object]()
        rowMap.put(fileHeader(0), row._1.asInstanceOf[Object])
        rowMap.put(fileHeader(1), row._2.getX.asInstanceOf[Object])
        rowMap.put(fileHeader(2), row._2.getY.asInstanceOf[Object])
        rowMap.put(fileHeader(3), row._3.asInstanceOf[Object])
        rowMap
      }
      rows.foreach(row => writer.write(row, fileHeader.toArray: _*))
    }
  }

}
