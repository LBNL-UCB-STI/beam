package beam.utils.map

import java.io.File

import beam.utils.GeoJsonReader
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Coordinate, Envelope, Geometry}
import org.apache.commons.io.FilenameUtils
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
    // Path to census tracts in GeoJSON (https://geojson.org/)
    // An example of file: https://github.com/LBNL-UCB-STI/beam/blob/production-sfbay/production/sfbay/calibration/san_francisco_censustracts.json
    val pathToGeoJson = args(0)

    val fileNameNoExt = FilenameUtils.removeExtension(new File(pathToGeoJson).getName)
    val outputFilePath = new File(pathToGeoJson).getParentFile.getPath

    val censusTractsPoints = readGeoJson(pathToGeoJson)
    GpxWriter.write(outputFilePath + "/" + fileNameNoExt + ".gpx", censusTractsPoints)
    renderEnvelope(censusTractsPoints, outputFilePath + "/" + fileNameNoExt + "_envelope.gpx")
  }
}
