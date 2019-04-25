package beam.utils.map

import beam.utils.GeoJsonReader
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Coordinate, Envelope, Geometry}
import org.matsim.api.core.v01.Coord
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

object GeoJsonToGpxConvertor extends LazyLogging {
  def renderEnvelope(points: Array[GpxPoint], outputPath: String): Unit = {
    val envelope = new Envelope(new Coordinate(points.head.wgsCoord.getX, points.head.wgsCoord.getY))
    points.foreach { p =>
      envelope.expandToInclude(p.wgsCoord.getX, p.wgsCoord.getY)
    }
    new EnvelopeToGpx().render(envelope, None, outputPath)
  }

  def readGeoJson(inputPath: String): Array[GpxPoint] = {
    val mapper: Feature => GpxPoint = (feature: Feature) => {
      val movementId = feature.getProperty("MOVEMENT_ID").getValue.toString
      val centroid = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry].getCentroid
      val wgsCoord = new Coord(centroid.getX, centroid.getY)
      GpxPoint(movementId, wgsCoord)
    }
    GeoJsonReader.read(inputPath, mapper)
  }

  def main(args: Array[String]): Unit = {
    {
      val censusTractsPoints = readGeoJson("""C:\temp\movement_data\san_francisco_censustracts.json""")
      GpxWriter.write("san_francisco_censustracts.gpx", censusTractsPoints)
      renderEnvelope(censusTractsPoints, "san_francisco_censustracts_envelope.gpx")
    }

    {
      val tazPoints = readGeoJson("""C:\temp\movement_data\san_francisco_taz.json""")
      GpxWriter.write("san_francisco_taz.gpx", tazPoints)
      renderEnvelope(tazPoints, "san_francisco_taz_envelope.gpx")
    }
  }
}
