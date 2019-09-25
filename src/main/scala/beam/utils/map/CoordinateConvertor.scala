package beam.utils.map

import beam.utils.csv.{CsvWriter, GenericCsvReader}
import org.matsim.api.core.v01.Coord

case class Data(time: Double, latitude: Double, longitude: Double)

object CoordinateConvertor {

  def main(args: Array[String]): Unit = {
    val path = """C:\temp\some_location.csv"""
    val outputPath = """C:\temp\some_location_converted.csv"""
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    def toCoord(map: java.util.Map[String, String]): Data = {
      val time = map.get("time").toDouble
      val latitude = map.get("Latitude").toDouble
      val longitude = map.get("Longitude").toDouble
      Data(time, latitude, longitude)
    }
    val (iter, toClose) = GenericCsvReader.readAs[Data](path, toCoord, x => true)
    try {
      val converted = iter.map { x =>
        val wgsCoord = geoUtils.wgs2Utm(new Coord(x.longitude, x.latitude))
        x.copy(longitude = wgsCoord.getX, latitude = wgsCoord.getY)
      }
      val csvWriter = new CsvWriter(outputPath, Array("time", "x", "y"))
      try {
        converted.foreach { data =>
          csvWriter.write(data.time, data.longitude, data.latitude)
        }
      } finally {
        csvWriter.close()
      }
    } finally {
      toClose.close()
    }
  }
}
