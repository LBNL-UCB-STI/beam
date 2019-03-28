package beam.utils
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Geometry
import org.matsim.api.core.v01.Coord
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

object GeoJsonToGpxConvertor extends LazyLogging {

  def convert(inputPath: String, outputPath: String): Unit = {
    val start = System.currentTimeMillis()
    val mapper: (Feature => GpxPoint) = (feature: Feature) => {
      val movementId = feature.getProperty("MOVEMENT_ID").getValue.toString
      val centroid = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry].getCentroid
      val wgsCoord = new Coord(centroid.getX, centroid.getY)
      GpxPoint(movementId, wgsCoord)
    }
    val points = GeoJsonReader.read(inputPath, mapper)
    GpxWriter.write(outputPath, points)
    val end = System.currentTimeMillis()
    logger.info(s"Converted '$inputPath' to '$outputPath' in ${end - start} ms")
  }

  def main(args: Array[String]): Unit = {
    convert("""C:\temp\movement_data\san_francisco_censustracts.json""", "san_francisco_censustracts.gpx")
    convert("""C:\temp\movement_data\san_francisco_taz.json""", "san_francisco_taz.gpx")
  }
}
