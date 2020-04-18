package beam.utils.map

import java.io.File
import java.util

import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Geometry => JtsGeometry}
import org.geotools.data.DataUtilities
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.feature.simple.{SimpleFeatureBuilder, SimpleFeatureTypeBuilder}
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.{Failure, Try}

class ShapeWriter[T <: JtsGeometry](
  private val crs: CoordinateReferenceSystem,
  private val path: String,
  private val attributes: Map[String, Class[_]]
)(implicit ct: ClassTag[T])
    extends StrictLogging {
  private val featureFactory: GenericFeatureBuilder =
    GenericFeatureBuilder.create[T](crs, ct.runtimeClass.getSimpleName, attributes)
  private val features: util.List[SimpleFeature] = new util.ArrayList[SimpleFeature]
  private val featureIds: mutable.Set[String] = mutable.Set[String]()
  private var isClosed: Boolean = false

  def add(geom: T, id: String, attributeValues: Map[String, Any] = Map.empty): Unit = {
    validateAttributes(attributeValues)
    if (featureIds.contains(id)) {
      logger.warn(s"Already saw feature with id ${id}. It won't be written into the shape file!")
    } else {
      featureIds.add(id)
      val feature = buildFeature(geom, id, attributeValues)
      features.add(feature)
    }
  }

  def write(): Try[Map[String, String]] = {
    if (!isClosed) {
      Try {
        val dataStore: ShapefileDataStore = initDataStore(featureFactory)
        val featureStore = dataStore.getFeatureSource.asInstanceOf[SimpleFeatureStore]
        val featureCollection = DataUtilities.collection(features)
        val fIterator = featureCollection.features()
        // This is needed because `DataUtilities.collection` can change the order - it uses SortedMap internally
        val originalFeatureIdsIt = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fIterator.hasNext
          override def next(): SimpleFeature = fIterator.next()
        }
        val persistedFeatureIds = featureStore.addFeatures(featureCollection)
        val originalToPersistedFid = originalFeatureIdsIt
          .map(_.getID)
          .toSeq
          .zip(persistedFeatureIds.asScala)
          .map {
            case (originalFeatureId, persistedFeatureId) =>
              originalFeatureId -> persistedFeatureId.getID
          }
          .toMap

        dataStore.dispose()
        features.clear()
        featureIds.clear()
        isClosed = true

        originalToPersistedFid
      }
    } else {
      Failure(
        new IllegalStateException(
          "It was already written before. You shouldn't call `write` multiple times, the second call won't do anything"
        )
      )
    }
  }

  private def buildFeature(geom: T, id: String, attributeValues: Map[String, Any]): SimpleFeature = {
    attributeValues.foreach {
      case (_, attribValue) =>
        featureFactory.add(attribValue)
    }
    featureFactory.add(geom)
    val feature = featureFactory.buildFeature(id)
    featureFactory.reset()
    feature
  }

  private def initDataStore(featureFactory: GenericFeatureBuilder): ShapefileDataStore = {
    val dataStore = new ShapefileDataStore(new File(path).toURI.toURL)
    dataStore.createSchema(featureFactory.getFeatureType)
    dataStore
  }

  private def validateAttributes(attributeValues: Map[String, Any]): Unit = {
    val extraKeys = attributes.keySet.diff(attributeValues.keySet)
    require(
      extraKeys.isEmpty,
      s"You're trying to write attributes which are not expected: $extraKeys. Expected to see the following attributes: ${attributes.keySet}"
    )
    attributeValues.foreach {
      case (attrib, value) =>
        val expectedClazz = attributes(attrib)
        require(
          value.getClass == expectedClazz,
          s"Provided class type ${value.getClass} for attribute $attrib does not much expected ${expectedClazz}"
        )
    }
  }
}

object ShapeWriter {

  def worldGeodetic[T <: JtsGeometry](path: String, attributes: Map[String, Class[_]])(
    implicit ct: ClassTag[T]
  ): ShapeWriter[T] = {
    // WGS84 is the same as EPSG:4326 https://epsg.io/4326
    new ShapeWriter(DefaultGeographicCRS.WGS84, path, attributes)
  }
}

class GenericFeatureBuilder(val attributes: Map[String, Class[_]], featureType: SimpleFeatureType)
    extends SimpleFeatureBuilder(featureType) {}

object GenericFeatureBuilder {

  def create[T <: JtsGeometry](crs: CoordinateReferenceSystem, name: String, attributes: Map[String, Class[_]])(
    implicit ct: ClassTag[T]
  ): GenericFeatureBuilder = {
    create(crs, name, attributes, ct.runtimeClass)
  }

  def create(
    crs: CoordinateReferenceSystem,
    name: String,
    attributes: Map[String, Class[_]],
    clazz: Class[_]
  ): GenericFeatureBuilder = {
    val b = new SimpleFeatureTypeBuilder
    b.setName(name)
    b.setCRS(crs)
    attributes.foreach {
      case (name, clazz) =>
        b.add(name, clazz)
    }
    b.add("the_geom", clazz)
    val featureType: SimpleFeatureType = b.buildFeatureType
    new GenericFeatureBuilder(attributes, featureType)
  }
}
