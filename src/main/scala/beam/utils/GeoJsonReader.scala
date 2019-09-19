package beam.utils

import java.io.{File, FileInputStream}

import com.typesafe.scalalogging.LazyLogging
import org.geotools.geojson.feature.FeatureJSON
import org.opengis.feature.Feature

import scala.reflect.ClassTag

object GeoJsonReader extends LazyLogging {

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
      logger.info(s"Read ${features.size} features from '$geoJsonPath' in ${end - start} ms")
      features
    } finally {
      fileIn.close()
    }
  }

  def main(args: Array[String]): Unit = {
    println(read[Feature]("""C:\temp\movement_data\san_francisco_censustracts.json""", x => x))
  }
}
