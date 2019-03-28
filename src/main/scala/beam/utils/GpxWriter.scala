package beam.utils
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils

case class GpxPoint(name: String, wgsCoord: Coord)

object GpxWriter {

  def write(filePath: String, points: Iterable[GpxPoint]): Unit = {
    val outWriter = IOUtils.getBufferedWriter(filePath)
    outWriter.write(
      """<?xml version="1.0" encoding="UTF-8" standalone="no" ?>
                      |<gpx version="1.1" creator="http://www.geoplaner.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://www.topografix.com/GPX/1/1" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""".stripMargin
    )
    try {
      points.foreach {
        case point =>
          val longitude = point.wgsCoord.getX
          val latitude = point.wgsCoord.getY
          val name = point.name
          outWriter.write(s"""<wpt lat="$latitude" lon="$longitude">""")
          outWriter.newLine()
          outWriter.write(s"""<name>$name</name>""")
          outWriter.newLine()
          outWriter.write("</wpt>")
          outWriter.newLine()
      }
    } finally {
      outWriter.write("</gpx>")

      outWriter.flush()
      outWriter.close()
    }
  }

}
