package beam.utils.geospatial

import com.typesafe.scalalogging.LazyLogging
import org.geotools.geojson.feature.FeatureJSON
import org.opengis.feature.Feature

import java.io.{File, FileInputStream}
import scala.reflect.ClassTag
import beam.agentsim.infrastructure.taz.TAZTreeMap.logger
import beam.utils.geospatial.SnapCoordinateUtils.SnapLocationHelper
import beam.utils.SortingUtil
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.{HasQuadBounds, QuadTreeBounds}
import org.geotools.data.{DataStoreFinder, FileDataStore, FileDataStoreFinder}
import org.geotools.data.simple.SimpleFeatureCollection
import org.locationtech.jts.geom.Geometry
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.GeometryUtils
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.io.IOUtils
import org.opengis.feature.simple.SimpleFeature
import org.slf4j.LoggerFactory
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.geotools.data.DataUtilities
import org.geotools.data.{DataStore, DataStoreFinder, Query}
import org.geotools.feature.FeatureCollection
import org.geotools.feature.FeatureIterator
import org.geotools.geojson.feature.FeatureJSON
import org.geotools.geojson.geom.GeometryJSON
import org.locationtech.jts.geom.Geometry

import java.io.File
import java.io._
import java.util
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
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

  def main(args: Array[String]): Unit = {
    println(
      read[Feature]("""C:\temp\movement_data\san_francisco_censustracts.json""", x => x).mkString("Array(", ", ", ")")
    )
  }
}
