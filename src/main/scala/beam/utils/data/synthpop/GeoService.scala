package beam.utils.data.synthpop

import java.io.{File, FileFilter}

import beam.utils.DebugLib
import beam.utils.data.synthpop.models.Models._
import beam.utils.map.ShapefileReader
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.geotools.geometry.jts.JTS
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.Try

case class GeoServiceInputParam(
  pathToTazShapeFolder: String,
  pathToBlockGroupShapeFolder: String,
  pathToOSMFile: String
)

class GeoService(param: GeoServiceInputParam, uniqueGeoIds: Set[BlockGroupGeoId]) extends StrictLogging {
  import GeoService._

  val mapBoundingBox: Envelope = GeoService.getBoundingBoxOfOsmMap(param.pathToOSMFile)

  private val crsCode: String = "EPSG:4326"

  val blockGroupGeoIdToGeom: Map[BlockGroupGeoId, Geometry] = {
    findShapeFile(param.pathToBlockGroupShapeFolder).flatMap { pathToShapeFile =>
      val map = getBlockGroupMap(pathToShapeFile.getPath, uniqueGeoIds).toSeq
      logger.info(s"Read geometries of ${map.size} BlockGroups from '${pathToShapeFile.getPath}'")
      map
    }.toMap
  }
  logger.info(s"blockGroupGeoIdToGeom: ${blockGroupGeoIdToGeom.size}")

  val tazGeoIdToGeom: Map[TazGeoId, Geometry] = {
    val stateAndCounty = uniqueGeoIds.map(x => (x.state, x.county))
    def filter(feature: SimpleFeature): Boolean = {
      val state = State(feature.getAttribute("STATEFP10").toString)
      val county = County(feature.getAttribute("COUNTYFP10").toString)
      uniqueGeoIds.isEmpty || stateAndCounty.contains((state, county))
    }
    findShapeFile(param.pathToTazShapeFolder).flatMap { pathToShapeFile =>
      val map = getTazMap(crsCode, pathToShapeFile.getPath, filter, defaultTazMapper)
      logger.info(s"Read geometries of ${map.size} TAZs from '${pathToShapeFile.getPath}'")
      map
    }.toMap
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
      if (state.value == "34" && county.value == "017") {
        DebugLib.emptyFunctionForSettingBreakPoint()
      }
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

  def getBoundingBoxOfOsmMap(path: String): Envelope = {
    val osm = new OSM(null)
    try {
      osm.readFromFile(path)

      var minX = Double.MaxValue
      var maxX = Double.MinValue
      var minY = Double.MaxValue
      var maxY = Double.MinValue

      osm.nodes.values().forEach { x =>
        val lon = x.getLon
        val lat = x.getLat

        if (lon < minX) minX = lon
        if (lon > maxX) maxX = lon
        if (lat < minY) minY = lat
        if (lat > maxY) maxY = lat
      }
      new Envelope(minX, maxX, minY, maxY)
    } finally {
      Try(osm.close())
    }
  }

  def findShapeFile(folderPath: String): IndexedSeq[File] = {
    val filter = new FileFilter {
      override def accept(pathname: File): Boolean = {
        pathname.isFile && pathname.canRead && pathname.getName.endsWith(".shp")
      }
    }
    val foundFiles = search(new File(folderPath), filter, Set.empty, Set.empty)
    require(
      foundFiles.nonEmpty,
      s"Could not find Shape files under folder '${folderPath}'. Please, make sure input is correct"
    )
    foundFiles.toArray.sorted
  }

  private def search(file: File, filter: FileFilter, result: Set[File], visited: Set[File]): Set[File] = {
    if (visited.contains(file)) {
      result
    } else {
      val files = file.listFiles()
      val folders = files.filter(f => f.isDirectory && f.canRead)
      val respectFilter = files.filter(f => filter.accept(f))
      val updatedVisited = visited + file
      val updatedResult = result ++ respectFilter

      val subTrees = folders.flatMap { folder =>
        search(folder, filter, updatedResult, updatedVisited)
      }.toSet
      val finalResult = updatedResult ++ subTrees
      finalResult
    }
  }

  def main(args: Array[String]): Unit = {
    val shapeFiles = findShapeFile("D:/Work/beam/NewYork/input/Shape/TAZ/")
    println(s"Found ${shapeFiles.size} Shape files")
    shapeFiles.foreach { file =>
      println(s"Shape path: ${file.getPath}")
    }
    println("D:/Work/beam/NewYork/input/Shape/TAZ/")
  }
}
