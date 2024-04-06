package beam.utils.geospatial

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import com.typesafe.scalalogging.LazyLogging
import org.geotools.geojson.feature.FeatureJSON
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature

import java.io.{BufferedReader, File, FileInputStream, FileReader}
import java.util
import scala.collection.JavaConverters._
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
        features
      }
  }

  def mapCBGToTAZ(cbgList: util.Collection[TAZ], tazGeo: TAZTreeMap): Map[String, String] = {
    cbgList.asScala
      .filter(_.geometry.nonEmpty)
      .flatMap { cbg =>
        var radius = Math.sqrt(cbg.areaInSquareMeters / Math.PI)
        var result: Option[(String, String)] = None
        while (result.isEmpty && radius < 50000) {
          result = tazGeo
            .getTAZInRadius(cbg.coord, radius)
            .asScala
            .filter(_.geometry.nonEmpty)
            .map { taz =>
              val intersectionArea = cbg.geometry.get.intersection(taz.geometry.get).getArea
              (taz, intersectionArea)
            }
            .reduceOption { (pair1: (TAZ, Double), pair2: (TAZ, Double)) =>
              if (pair1._2 > pair2._2) pair1 else pair2
            }
            .map(taz => cbg.tazId.toString -> taz._1.tazId.toString)
          radius = radius * 5
        }
        result
      }
      .toMap
  }

  def main(args: Array[String]): Unit = {
    readFeatures("test/input/sf-light/shape/sf_cbgs.geojson")
  }
}
