package beam.utils.map

import java.io.BufferedWriter

import beam.sim.common.GeoUtils
import beam.utils.map.EnvelopeToGpx.{latitude, longitude}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils

import scala.util.Try

case class GpxPoint(name: String, wgsCoord: Coord)

class GpxWriter(filePath: String, geoUtils: GeoUtils) extends AutoCloseable {

  import GpxWriter._

  val outWriter: BufferedWriter = {
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(XML_HEADER)
    writer.newLine()

    writer.write(GPX_START)
    writer.newLine()
    writer
  }

  def drawRectangle(envelopePoints: Array[GpxPoint]): Unit = {
    GpxWriter.drawRectangle(outWriter, geoUtils, envelopePoints)
  }

  def drawMarker(point: GpxPoint): Unit = {
    GpxWriter.drawMarker(outWriter, point)
  }

  def drawSourceToDest(source: GpxPoint, dest: GpxPoint): Unit = {
    GpxWriter.drawSourceToDest(outWriter, geoUtils, source, dest)
  }

  override def close(): Unit = {
    Try(outWriter.write(GPX_END))
    outWriter.flush()
    outWriter.close()
  }
}

object GpxWriter {
  val XML_HEADER: String = """<?xml version="1.0" encoding="UTF-8" standalone="no" ?>"""

  val GPX_START: String =
    """<gpx version="1.1" creator="http://www.geoplaner.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://www.topografix.com/GPX/1/1" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">"""

  val GPX_END: String = "</gpx>"

  def write(filePath: String, points: Iterable[GpxPoint]): Unit = {
    val outWriter = IOUtils.getBufferedWriter(filePath)
    outWriter.write(XML_HEADER)
    outWriter.newLine()

    outWriter.write(GPX_START)
    outWriter.newLine()

    try {
      points.foreach { point =>
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
      outWriter.write(GPX_END)

      outWriter.flush()
      outWriter.close()
    }
  }

  def drawRectangle(outWriter: BufferedWriter, geoUtils: GeoUtils, envelopePoints: Array[GpxPoint]): Unit = {
    val p = envelopePoints.sliding(2).toArray :+ Array(envelopePoints(0), envelopePoints(3))

    p.foreach { p =>
      val source = p(0)
      val dest = p(1)
      drawSourceToDest(outWriter, geoUtils, source, dest)
    }
  }

  def drawMarker(outWriter: BufferedWriter, point: GpxPoint): Unit = {
    outWriter.write(s"""<wpt lat="${latitude(point.wgsCoord)}" lon="${longitude(point.wgsCoord)}">""")
    outWriter.newLine()
    outWriter.write(s"""<name>${point.name}</name>""")
    outWriter.newLine()
    outWriter.write("</wpt>")
    outWriter.newLine()
  }

  def drawSourceToDest(outWriter: BufferedWriter, geoUtils: GeoUtils, source: GpxPoint, dest: GpxPoint): Unit = {
    outWriter.write("<rte>")
    outWriter.newLine()
    outWriter.write(
      s"""<rtept lon="${longitude(source.wgsCoord)}" lat="${latitude(source.wgsCoord)}"><name>Src</name></rtept>"""
    )
    outWriter.newLine()
    outWriter.write(
      s"""<rtept lon="${longitude(dest.wgsCoord)}" lat="${latitude(dest.wgsCoord)}"><name>Dest</name></rtept>"""
    )
    outWriter.newLine()

    val distanceUTM = geoUtils.distUTMInMeters(geoUtils.wgs2Utm(source.wgsCoord), geoUtils.wgs2Utm(dest.wgsCoord))

    outWriter.write(s"""<name>Distance: $distanceUTM</name>""")
    outWriter.newLine()
    outWriter.write("</rte>")
    outWriter.newLine()
  }

}
