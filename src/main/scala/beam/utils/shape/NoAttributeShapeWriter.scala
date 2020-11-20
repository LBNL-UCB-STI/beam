package beam.utils.shape
import beam.utils.shape.ShapeWriter.OriginalToPersistedFeatureIdMap
import com.vividsolutions.jts.geom.{Geometry => JtsGeometry}
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.reflect.ClassTag
import scala.util.Try

class NoAttributeShapeWriter[G <: JtsGeometry](private val crs: CoordinateReferenceSystem, private val path: String)(
  implicit evG: ClassTag[G]
) {
  private val shareWriter = new ShapeWriter[G, Attributes.EmptyAttributes.type](crs, path)

  def add(geom: G, id: String): Unit = {
    shareWriter.add(geom, id, Attributes.EmptyAttributes)
  }

  def write(): Try[OriginalToPersistedFeatureIdMap] = {
    shareWriter.write()
  }
}

object NoAttributeShapeWriter {

  def worldGeodetic[G <: JtsGeometry](path: String)(
    implicit evG: ClassTag[G]
  ): NoAttributeShapeWriter[G] = {
    // WGS84 is the same as EPSG:4326 https://epsg.io/4326
    new NoAttributeShapeWriter[G](DefaultGeographicCRS.WGS84, path)
  }
}
