package beam.utils.map

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord

class EnvelopeToGpx extends LazyLogging {

  val geoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "EPSG:4326"
  }

  def render(envelope: Envelope, wgsCoordOpt: Option[Coord], outputPath: String): Unit = {
    val start = System.currentTimeMillis()

    // We have min(x0, y0) and max(x1,y1). Need to add two extra points to draw rectangle
    /*
    min => x0,y0
    max => x1,y1
x0,y1 .___________. x1,y1
      |           |
      |           |
      |           |
      |           |
x0,y0 .___________. x1, y0
     */

    val envelopePoints = Array[GpxPoint](
      GpxPoint("x0,y0", new Coord(envelope.getMinX, envelope.getMinY)),
      GpxPoint("x0,y1", new Coord(envelope.getMinX, envelope.getMaxY)),
      GpxPoint("x1,y1", new Coord(envelope.getMaxX, envelope.getMaxY)),
      GpxPoint("x1,y0", new Coord(envelope.getMaxX, envelope.getMinY))
    )

    val gpxWriter = new GpxWriter(outputPath, geoUtils)
    try {

      wgsCoordOpt.foreach { wgsCoord =>
        val middle = GpxPoint(
          "Middle",
          new Coord((envelope.getMinX + envelope.getMaxX) / 2, (envelope.getMinY + envelope.getMaxY) / 2)
        )
        gpxWriter.drawMarker(middle)
        val searchPoint = GpxPoint("Search", wgsCoord)
        gpxWriter.drawMarker(searchPoint)
        gpxWriter.drawSourceToDest(middle, searchPoint)
      }

      envelopePoints.foreach(point => gpxWriter.drawMarker(point))

      gpxWriter.drawRectangle(envelopePoints)

    } finally {
      gpxWriter.close()
    }
    val end = System.currentTimeMillis()
    logger.info(s"Created '$outputPath' in ${end - start} ms")
  }
}

object EnvelopeToGpx {
  def longitude(coord: Coord): Double = coord.getX

  def latitude(coord: Coord): Double = coord.getY

  def main(args: Array[String]): Unit = {
    // -75.8600, 40.8800, -73.2500, 45.0300
    val en1 = new Envelope(-75.8600, -73.2500, 40.8800, 45.0300)
    val envelopeToGpx1 = new EnvelopeToGpx
    envelopeToGpx1.render(en1, None, "envelope_of_EPSG:2260.gpx")

    // -74.2700, -71.7500, 40.4700, 41.3100
    val en2 = new Envelope(-74.2700, -71.7500, 40.4700, 41.3100)
    val envelopeToGpx2 = new EnvelopeToGpx
    envelopeToGpx2.render(en2, None, "envelope_of_EPSG:2263.gpx")

    // val en1 = new Envelope(-74.5999979, -73.6000019,  41.2999981,  40.300004)
    val en3 = new Envelope(-74.5999979, -73.6000019,  41.2999981,  40.300004)
    val envelopeToGpx3 = new EnvelopeToGpx
    envelopeToGpx3.render(en3, None, "envelope_of_MAP.gpx")
  }
}
