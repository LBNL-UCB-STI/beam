package census.db.creator.service.shape
import java.io.File

import census.db.creator.domain.TazInfo
import com.vividsolutions.jts.geom.Geometry
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.data.store.{ContentFeatureCollection, ContentFeatureSource}

import scala.collection.mutable

private[creator] class ShapefileRepo(path: String) extends AutoCloseable {

  private val dataStore = new ShapefileDataStore(new File(path).toURI.toURL)

  def getFeatures(): Seq[TazInfo] = {
    val featureSource: ContentFeatureSource = dataStore.getFeatureSource
    val featureCollection: ContentFeatureCollection = featureSource.getFeatures

    val iterator: SimpleFeatureIterator = featureCollection.features
    val arrayBuffer = mutable.ArrayBuffer[TazInfo]()
    while ({ iterator.hasNext }) {
      val feature = iterator.next
      val attributes = feature.getAttributes
      val properties = feature.getProperties
      arrayBuffer += TazInfo(
        feature.getAttribute("GEOID").toString,
        feature.getAttribute("STATEFP").toString,
        feature.getAttribute("COUNTYFP").toString,
        feature.getAttribute("TRACTCE").toString,
        feature.getAttribute("ALAND").toString.toLong,
        feature.getAttribute("AWATER").toString.toLong,
        feature.getDefaultGeometry.asInstanceOf[Geometry]
      )
    }
    iterator.close()
    arrayBuffer
  }

  override def close(): Unit = {
    dataStore.dispose()
  }
}
