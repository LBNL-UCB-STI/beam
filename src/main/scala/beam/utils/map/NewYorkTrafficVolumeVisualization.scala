package beam.utils.map

import beam.utils.csv.GenericCsvReader
import beam.utils.shape.{Attributes, ShapeWriter}
import beam.utils.{FileUtils, GeoJsonReader}
import com.vividsolutions.jts.geom.{Geometry, MultiLineString}
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

private case class GeoAttribute(segmentId: Int, street: String) extends Attributes

object NewYorkTrafficVolumeVisualization {

  // How to run through gradle
  /*
  ./gradlew :execute \
      -PmainClass=beam.utils.map.NewYorkTrafficVolumeVisualization \
      -PappArgs="['C:/Users/User/Downloads/LION.geojson', '04/11/2018']" \
      -PmaxRAM=8g
   */
  def main(args: Array[String]): Unit = {
    // You need to replace the content of that file instead being `urn:ogc:def:crs:OGC:1.3:CRS84` to be `EPSG:4326`
    val pathToGeoJson: String = args(0) // "C:/Users/User/Downloads/LION.geojson"
    val pathToVolumeCsv: String = "https://data.cityofnewyork.us/api/views/ertz-hr4r/rows.csv?accessType=DOWNLOAD"
    val date = args(1) // "04/11/2018" // Format is dd/MM/YYYY

    val segmentIds: Set[Int] = {
      val (it, toClose) = GenericCsvReader.readFromStreamAs[(String, String)](
        FileUtils.getInputStream(pathToVolumeCsv),
        x => (x.get("Segment ID"), x.get("Date")),
        x => x._2 == date
      )
      try { it.map(_._1.toInt).toSet } finally { toClose.close() }
    }
    println(s"Read ${segmentIds.size} unique segmentIds for the date $date")

    val allFeatures = GeoJsonReader.read(pathToGeoJson, mapper)
    val filteredFeatures = allFeatures.filter { case (attr, _) => segmentIds.contains(attr.segmentId) }
    println(s"allFeatures: ${allFeatures.length}")
    println(s"filteredFeatures: ${filteredFeatures.length}")
    println(s"filteredFeatures (by unique segment id): ${filteredFeatures.map(_._1.segmentId).distinct.length}")

    val shapeFileName = "traffic_volume_ny_" + date.replace("/", "_")
    val shapeWriter = ShapeWriter.worldGeodetic[MultiLineString, GeoAttribute](s"$shapeFileName.shp")
    filteredFeatures.zipWithIndex.foreach {
      case ((attr, geom), idx) =>
        shapeWriter.add(geom.asInstanceOf[MultiLineString], idx.toString, attr)
    }
    shapeWriter.write()
  }

  private def mapper(feature: Feature): (GeoAttribute, Geometry) = {
    val geom = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry]
    val segmentId = feature.getProperty("SegmentID").getValue.toString.toInt
    val street = feature.getProperty("Street").getValue.toString
    (GeoAttribute(segmentId, street), geom)
  }
}
