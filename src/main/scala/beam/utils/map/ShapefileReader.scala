package beam.utils.map

import java.io.File

import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.data.shapefile.shp.ShapefileException
import org.geotools.referencing.CRS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

object ShapefileReader {

  def read[T: ClassTag](
    crsCode: String,
    path: String,
    filter: SimpleFeature => Boolean,
    mapper: (MathTransform, SimpleFeature) => T
  ): Array[T] = {
    val dataStore = new ShapefileDataStore(new File(path).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      val destinationCoordSystem = CRS.decode(crsCode, true)
      val mt: MathTransform =
        CRS.findMathTransform(dataStore.getSchema.getCoordinateReferenceSystem, destinationCoordSystem, true)
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        it.filter(filter).map(mapper(mt, _)).toArray
      } catch {
        case NonFatal(ex) =>
          throw new ShapefileException(s"Error during reading shape file '$path'", ex)
      } finally {
        Try(fe.close())
      }
    } finally {
      Try(dataStore.dispose())
    }
  }
}
