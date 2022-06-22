package beam.utils.map

import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord

class EnvelopeToGpx extends LazyLogging {

  val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:26910"
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
    val en: Envelope = new Envelope(-122.546046192, -122.330412968, 37.655512625, 37.811362211)
    val envelopeToGpx = new EnvelopeToGpx
    envelopeToGpx.render(en, None, "ex1.gpx")
  }
}
