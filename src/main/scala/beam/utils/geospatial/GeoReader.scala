package beam.utils.geospatial

import com.typesafe.scalalogging.LazyLogging
import org.geotools.geojson.feature.FeatureJSON
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

import java.io.{BufferedReader, File, FileInputStream, FileReader}
import java.util
import scala.reflect.ClassTag
import scala.util.Using

object GeoReader extends LazyLogging {

  def read[T](geoJsonPath: String, mapper: Feature => T)(implicit ct: ClassTag[T]): Array[T] = {
    val start = System.currentTimeMillis()
    val fileIn = new FileInputStream(new File(geoJsonPath))
    try {
      val featureIterator = new FeatureJSON().readFeatureCollection(fileIn).features()
      val it = new Iterator[Feature] {
        override def hasNext: Boolean = featureIterator.hasNext
        override def next(): Feature = featureIterator.next()
      }
      val features = it.map(mapper).toArray
      val end = System.currentTimeMillis()
      logger.info(s"Read ${features.length} features from '$geoJsonPath' in ${end - start} ms")
      features
    } finally {
      fileIn.close()
    }
  }

  def readFeatures(filePath: String): util.Collection[SimpleFeature] = filePath match {
    case path if path.endsWith(".shp")     => readShapefileFeatures(path)
    case path if path.endsWith(".geojson") => readGeoJSONFeatures(path)
    case _                                 => throw new IllegalArgumentException("Unsupported file format")
  }

  private def readShapefileFeatures(filePath: String): util.Collection[SimpleFeature] = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(filePath)
    shapeFileReader.getFeatureSet
  }

  private def readGeoJSONFeatures(filePath: String): util.Collection[SimpleFeature] = {
    Using
      .resource(new BufferedReader(new FileReader(new File(filePath)))) { reader =>
        val featureJSON = new FeatureJSON()
        val featureIterator = featureJSON.streamFeatureCollection(reader)
        val features = new util.ArrayList[SimpleFeature]()

        try {
          while (featureIterator.hasNext) {
            features.add(featureIterator.next())
          }
        } finally {
          featureIterator.close()
        }
        print(features)
        features
      }
  }

  def main(args: Array[String]): Unit = {
    //    println(
    //      read[Feature]("""C:\temp\movement_data\san_francisco_censustracts.json""", x => x).mkString("Array(", ", ", ")")
    //    )

    readGeoJSONFeatures("/Users/haitamlaarabi/Workspace/Data/Scenarios/sfbay/input/sfbay_cbgs.geojson")
  }
}
