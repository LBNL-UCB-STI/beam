package beam.utils.data.synthpop

import beam.utils.data.synthpop.models.Models._
import beam.utils.map.ShapefileReader
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import scala.reflect.ClassTag

case class GeoServiceInputParam(
  pathToTazShapeFile: String,
  pathToBlockGroupShapeFile: String
)

class GeoService(param: GeoServiceInputParam, uniqueStates: Set[State], uniqueGeoIds: Set[BlockGroupGeoId])
    extends StrictLogging {
  import GeoService._

  private val crsCode: String = "EPSG:4326"

  val blockGroupGeoIdToGeom: Map[BlockGroupGeoId, Geometry] =
    getBlockGroupMap(param.pathToBlockGroupShapeFile, uniqueGeoIds)
  logger.info(s"blockGroupGeoIdToGeom: ${blockGroupGeoIdToGeom.size}")

  val tazGeoIdToGeom: Map[TazGeoId, Geometry] = {
    val stateAndCounty = uniqueGeoIds.map(x => (x.state, x.county))
    def filter(feature: SimpleFeature): Boolean = {
      val state = State(feature.getAttribute("STATEFP10").toString)
      val county = County(feature.getAttribute("COUNTYFP10").toString)
      uniqueGeoIds.isEmpty || stateAndCounty.contains((state), county)
    }
    getTazMap(crsCode, param.pathToTazShapeFile, filter, defaultTazMapper).toMap
  }
  logger.info(s"tazGeoIdToGeom: ${tazGeoIdToGeom.size}")

  def getBlockGroupMap(
    pathToBlockGroupShapeFile: String,
    uniqueGeoIds: Set[BlockGroupGeoId]
  ): Map[BlockGroupGeoId, Geometry] = {
    def filter(feature: SimpleFeature): Boolean = {
      val state = State(feature.getAttribute("STATEFP").toString)
      val county = County(feature.getAttribute("COUNTYFP").toString)
      val tract = feature.getAttribute("TRACTCE").toString
      val blockGroup = feature.getAttribute("BLKGRPCE").toString
      val shouldConsider = uniqueGeoIds.contains(
        BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup)
      )
      shouldConsider
    }
    def map(mathTransform: MathTransform, feature: SimpleFeature): (BlockGroupGeoId, Geometry) = {
      val state = State(feature.getAttribute("STATEFP").toString)
      val county = County(feature.getAttribute("COUNTYFP").toString)
      val tract = feature.getAttribute("TRACTCE").toString
      val blockGroup = feature.getAttribute("BLKGRPCE").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup) -> wgsGeom
    }

    ShapefileReader.read(crsCode, pathToBlockGroupShapeFile, filter, map).toMap
  }

  def getPlaceOfWorkPumaMap(pathToPowPumaShapeFile: String, uniqueStates: Set[State]): Map[PowPumaGeoId, Geometry] = {
    def filter(feature: SimpleFeature): Boolean = {
      val state = State(feature.getAttribute("PWSTATE").toString)
      uniqueStates.contains(state)
    }
    def map(mathTransform: MathTransform, feature: SimpleFeature): (PowPumaGeoId, Geometry) = {
      val state = feature.getAttribute("PWSTATE").toString
      val puma = feature.getAttribute("PWPUMA").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      PowPumaGeoId(State(state), puma) -> wgsGeom
    }

    ShapefileReader.read(crsCode, pathToPowPumaShapeFile, filter, map).toMap
  }

  def getPumaMap(pathToPumaShapeFile: String): Map[PumaGeoId, Geometry] = {
    def map(mathTransform: MathTransform, feature: SimpleFeature): (PumaGeoId, Geometry) = {
      val state = feature.getAttribute("STATEFP10").toString
      val puma = feature.getAttribute("PUMACE10").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      PumaGeoId(State(state), puma) -> wgsGeom
    }
    ShapefileReader.read(crsCode, pathToPumaShapeFile, x => true, map).toMap
  }
}

object GeoService {

  def defaultTazMapper(mathTransform: MathTransform, feature: SimpleFeature): (TazGeoId, Geometry) = {
    val state = State(feature.getAttribute("STATEFP10").toString)
    val county = County(feature.getAttribute("COUNTYFP10").toString)
    val taz = feature.getAttribute("TAZCE10").toString
    val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
    val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
    TazGeoId(state, county, taz) -> wgsGeom
  }

  def getTazMap[T: ClassTag](
    crsCode: String,
    pathToTazShapeFile: String,
    filter: SimpleFeature => Boolean,
    mapper: (MathTransform, SimpleFeature) => T
  ): Seq[T] = {

    ShapefileReader.read(crsCode, pathToTazShapeFile, filter, mapper)
  }
}
