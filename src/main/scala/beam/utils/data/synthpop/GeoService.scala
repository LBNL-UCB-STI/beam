package beam.utils.data.synthpop

import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, PowPumaGeoId, PumaGeoId}
import beam.utils.map.ShapefileReader
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

case class GeoServiceInputParam(
  pathToPumaShapeFile: String,
  pathToPowPumaShapeFile: String,
  pathToBlockGroupShapeFile: String
)

class GeoService(param: GeoServiceInputParam, uniqueStates: Set[String], uniqueGeoIds: Set[BlockGroupGeoId])
    extends StrictLogging {
  private val crsCode: String = "epsg:26910"

  val pumaGeoIdToGeom: Map[PumaGeoId, Geometry] = getPumaMap
  logger.info(s"pumaIdToMap: ${pumaGeoIdToGeom.size}")

  val powPumaGeoIdMap: Map[PowPumaGeoId, Geometry] = getPlaceOfWorkPumaMap(uniqueStates)
  logger.info(s"powPumaGeoIdMap: ${powPumaGeoIdMap.size}")

  val blockGroupGeoIdToGeom: Map[BlockGroupGeoId, Geometry] = getBlockGroupMap(uniqueGeoIds)
  logger.info(s"blockGroupGeoIdToGeom: ${blockGroupGeoIdToGeom.size}")

  private def getPlaceOfWorkPumaMap(uniqueStates: Set[String]): Map[PowPumaGeoId, Geometry] = {
    def filter(feature: SimpleFeature): Boolean = {
      val state = feature.getAttribute("PWSTATE").toString
      uniqueStates.contains(state)
    }
    def map(mathTransform: MathTransform, feature: SimpleFeature): (PowPumaGeoId, Geometry) = {
      val state = feature.getAttribute("PWSTATE").toString
      val puma = feature.getAttribute("PWPUMA").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      PowPumaGeoId(state, puma) -> wgsGeom
    }

    ShapefileReader.read(crsCode, param.pathToPowPumaShapeFile, filter, map).toMap
  }

  private def getPumaMap: Map[PumaGeoId, Geometry] = {
    def map(mathTransform: MathTransform, feature: SimpleFeature): (PumaGeoId, Geometry) = {
      val state = feature.getAttribute("STATEFP10").toString
      val puma = feature.getAttribute("PUMACE10").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      PumaGeoId(state, puma) -> wgsGeom
    }
    ShapefileReader.read(crsCode, param.pathToPumaShapeFile, x => true, map).toMap
  }

  private def getBlockGroupMap(uniqueGeoIds: Set[BlockGroupGeoId]): Map[BlockGroupGeoId, Geometry] = {
    def filter(feature: SimpleFeature): Boolean = {
      val state = feature.getAttribute("STATEFP").toString
      val county = feature.getAttribute("COUNTYFP").toString
      val tract = feature.getAttribute("TRACTCE").toString
      val blockGroup = feature.getAttribute("BLKGRPCE").toString
      val shouldConsider = uniqueGeoIds.contains(
        BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup)
      )
      shouldConsider
    }
    def map(mathTransform: MathTransform, feature: SimpleFeature): (BlockGroupGeoId, Geometry) = {
      val state = feature.getAttribute("STATEFP").toString
      val county = feature.getAttribute("COUNTYFP").toString
      val tract = feature.getAttribute("TRACTCE").toString
      val blockGroup = feature.getAttribute("BLKGRPCE").toString
      val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
      val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
      BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup) -> wgsGeom
    }

    ShapefileReader.read(crsCode, param.pathToBlockGroupShapeFile, filter, map).toMap
  }
}
