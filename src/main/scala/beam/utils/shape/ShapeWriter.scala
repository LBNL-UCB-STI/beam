package beam.utils.shape

import java.io.File
import java.util

import beam.utils.shape.Attributes.EmptyAttributes
import beam.utils.shape.ShapeWriter.OriginalToPersistedFeatureIdMap
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Geometry => JtsGeometry}
import org.geotools.data.DataUtilities
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.simple.SimpleFeatureStore
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.{Failure, Try}

trait Attributes extends Product

object Attributes {
  case object EmptyAttributes extends Attributes
}

class ShapeWriter[G <: JtsGeometry, A <: Attributes](
  private val crs: CoordinateReferenceSystem,
  private val path: String
)(implicit evG: ClassTag[G], evA: ClassTag[A])
    extends StrictLogging {

  private val featureFactory: GenericFeatureBuilder[A] =
    GenericFeatureBuilder.create[G, A](crs, evG.runtimeClass.getSimpleName)
  private val features: util.List[SimpleFeature] = new util.ArrayList[SimpleFeature]
  private val featureIds: mutable.Set[String] = mutable.Set[String]()
  private var isWritten: Boolean = false

  def add(geom: G, id: String, attribute: A): Unit = {
    if (featureIds.contains(id)) {
      logger.warn(s"Already saw feature with id ${id}. It won't be written into the shape file!")
    } else {
      featureIds.add(id)
      // Check for special case when no attribute
      val maybeAttribute = if (EmptyAttributes eq attribute) None else Some(attribute)
      val feature = buildFeature(geom, id, maybeAttribute)
      features.add(feature)
    }
  }

  def write(): Try[OriginalToPersistedFeatureIdMap] = {
    if (isWritten) {
      Failure(
        new IllegalStateException(
          "It was already written before. You shouldn't call `write` multiple times, the second call won't do anything"
        )
      )
    } else {
      Try {
        val dataStore: ShapefileDataStore = ShapeWriter.initDataStore(featureFactory, path)
        val featureStore = dataStore.getFeatureSource.asInstanceOf[SimpleFeatureStore]
        val featureCollection = DataUtilities.collection(features)
        val fIterator = featureCollection.features()
        // This is needed because `DataUtilities.collection` can change the order - it uses SortedMap internally
        val originalFeatureIdsIt = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fIterator.hasNext
          override def next(): SimpleFeature = fIterator.next()
        }
        val persistedFeatureIds = featureStore.addFeatures(featureCollection)
        val originalToPersistedFid: Map[String, String] = originalFeatureIdsIt
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
        isWritten = true

        OriginalToPersistedFeatureIdMap(originalToPersistedFid)
      }
    }
  }

  private def buildFeature(geom: G, id: String, maybeAttributes: Option[A]): SimpleFeature = {
    featureFactory.add(geom)
    maybeAttributes.foreach { attrib =>
      featureFactory.addAttributes(attrib)
    }
    featureFactory.buildFeature(id)
  }

}

object ShapeWriter {
  case class OriginalToPersistedFeatureIdMap(map: Map[String, String])

  def worldGeodetic[G <: JtsGeometry, A <: Attributes](path: String)(
    implicit evG: ClassTag[G],
    evA: ClassTag[A]
  ): ShapeWriter[G, A] = {
    // WGS84 is the same as EPSG:4326 https://epsg.io/4326
    new ShapeWriter[G, A](DefaultGeographicCRS.WGS84, path)
  }

  private def initDataStore(
    featureFactory: GenericFeatureBuilder[_],
    path: String
  ): ShapefileDataStore = {
    val dataStore = new ShapefileDataStore(new File(path).toURI.toURL)
    dataStore.createSchema(featureFactory.getFeatureType)
    dataStore
  }

}
