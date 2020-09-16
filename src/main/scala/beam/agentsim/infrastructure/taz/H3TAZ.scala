package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.H3TAZ.{fillBox, toCoord, H3, HexIndex}
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.typesafe.scalalogging.StrictLogging
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}
import scala.collection.JavaConverters._
import scala.collection._

import beam.agentsim.infrastructure.geozone.{H3Index, H3Wrapper, WgsCoordinate}

case class H3TAZ(network: Network, tazTreeMap: TAZTreeMap, beamConfig: BeamConfig) extends StrictLogging {
  private def cfg = beamConfig.beam.agentsim.h3taz
  if (cfg.lowerBoundResolution > cfg.upperBoundResolution) logger.error("lowerBoundResolution > upperBoundResolution")
  private val toH3CoordSystem =
    new GeotoolsTransformation(beamConfig.matsim.modules.global.coordinateSystem, H3TAZ.H3Projection)
  private val toScenarioCoordSystem =
    new GeotoolsTransformation(H3TAZ.H3Projection, beamConfig.matsim.modules.global.coordinateSystem)

  private val boundingBox: QuadTreeBounds = H3TAZ.quadTreeExtentFromShapeFile(
    network.getNodes.values().asScala.map(n => toH3CoordSystem.transform(n.getCoord))
  )
  private val fillBoxResult: Iterable[String] =
    ProfilingUtils.timed(
      s"fillBox for boundingBox $boundingBox with resolution $getResolution",
      str => logger.info(str)
    ) {
      fillBox(boundingBox, getResolution)
    }
  logger.info(
    s"fillBox for boundingBox $boundingBox with resolution $getResolution gives ${fillBoxResult.size} elemets"
  )

  private val tazToH3TAZMapping: Map[HexIndex, Id[TAZ]] =
    ProfilingUtils.timed("Constructed tazToH3TAZMapping", str => logger.info(str)) {
      fillBoxResult.par
        .map { hex =>
          val centroid = getCentroid(hex)
          val tazId = tazTreeMap.getTAZ(centroid.getX, centroid.getY).tazId
          (hex, tazId)
        }
        .toMap
        .seq
    }

  def getAll: Iterable[HexIndex] = tazToH3TAZMapping.keys
  def getIndices(tazId: Id[TAZ]): Iterable[HexIndex] = tazToH3TAZMapping.filter(_._2 == tazId).keys
  def getTAZ(hex: HexIndex): Id[TAZ] = tazToH3TAZMapping.getOrElse(hex, TAZTreeMap.emptyTAZId)
  def getIndex(x: Double, y: Double): HexIndex = getIndex(new Coord(x, y))
  def getCentroid(hex: HexIndex): Coord = toScenarioCoordSystem.transform(toCoord(H3.h3ToGeo(hex)))

  def getSubIndex(c: Coord): Option[HexIndex] = {
    if (getResolution + 1 <= cfg.upperBoundResolution) {
      val coord = H3TAZ.toGeoCoord(toH3CoordSystem.transform(c))
      Some(H3TAZ.H3.geoToH3Address(coord.lat, coord.lng, getResolution + 1))
    } else None
  }

  def getIndex(c: Coord): HexIndex = {
    val coord = H3TAZ.toGeoCoord(toH3CoordSystem.transform(c))
    H3TAZ.H3.geoToH3Address(coord.lat, coord.lng, getResolution)
  }
  def getResolution: Int = cfg.lowerBoundResolution
}

object H3TAZ {
  type HexIndex = String
  private val H3 = com.uber.h3core.H3Core.newInstance
  val H3Projection = "EPSG:4326"

  def writeToShp(filename: String, h3Tazs: Iterable[(HexIndex, String, Double)]): Unit = {
    val gf = new GeometryFactory()
    val hexagons = h3Tazs.map {
      case (h, taz, v) =>
        val boundary = H3.h3ToGeoBoundary(h).asScala
        (h, taz, v, gf.createPolygon(boundary.map(toJtsCoordinate).toArray :+ toJtsCoordinate(boundary.head)))
    }
    val pf: PolygonFeatureFactory = new PolygonFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .addAttribute("ID", classOf[String])
      .addAttribute("TAZ", classOf[String])
      .addAttribute("VALUE", classOf[java.lang.Double])
      .create()
    val shpPolygons = hexagons.map {
      case (hex, taz, value, hexagon) =>
        pf.createPolygon(hexagon.getCoordinates, Array[Object](hex, taz, value.toString), null)
    }
    ShapeFileWriter.writeGeometries(shpPolygons.asJavaCollection, filename)
  }

  // private utilities
  private def toJtsCoordinate(in: GeoCoord): com.vividsolutions.jts.geom.Coordinate = {
    new com.vividsolutions.jts.geom.Coordinate(in.lng, in.lat)
  }
  private def toGeoCoord(in: Coord): GeoCoord = {
    new GeoCoord(in.getY, in.getX)
  }
  private def toCoord(in: GeoCoord): Coord = {
    new Coord(in.lng, in.lat)
  }

  private def quadTreeExtentFromShapeFile(coords: Iterable[Coord]): QuadTreeBounds = {
    val bounds = ShapeUtils.quadTreeBounds(coords)
    val (minX, maxX, minY, maxY) = (bounds.minx, bounds.maxx, bounds.miny, bounds.maxy)
    val gf = new GeometryFactory()
    val box = gf
      .createPolygon(
        Array(
          new Coordinate(minX, minY),
          new Coordinate(minX, maxY),
          new Coordinate(maxX, maxY),
          new Coordinate(maxX, minY),
          new Coordinate(minX, minY)
        )
      )
      .asInstanceOf[Geometry]
      .getEnvelopeInternal
    QuadTreeBounds(box.getMinX - 0.01, box.getMinY - 0.01, box.getMaxX + 0.01, box.getMaxY + 0.01)
  }

  private def fillBox(box: QuadTreeBounds, resolution: Int): Iterable[String] = {
    val points = List(
      new GeoCoord(box.miny, box.minx),
      new GeoCoord(box.maxy, box.minx),
      new GeoCoord(box.maxy, box.maxx),
      new GeoCoord(box.miny, box.maxx)
    ).asJava
    val holes = List.empty[java.util.List[GeoCoord]].asJava
    H3.polyfillAddress(points, holes, resolution).asScala
  }

  def getDataPointsInferredH3IndexSet(
    dataPoints: Array[WgsCoordinate],
    maxNumberOfDataPoints: Int,
    lowestResolution: Int,
    highestResolution: Int
  ): Array[(H3Index, Array[WgsCoordinate])] = {
    val indexing = dataPoints.groupBy(coord => H3Wrapper.getIndex(coord, lowestResolution)).toArray
    val (a, b) = indexing.partition(_._2.length > maxNumberOfDataPoints)
    if (lowestResolution == highestResolution || a.isEmpty)
      indexing
    else {
      val inferredDataPoints = getDataPointsInferredH3IndexSet(
        dataPoints = a.flatMap(_._2),
        maxNumberOfDataPoints = maxNumberOfDataPoints,
        lowestResolution = lowestResolution + 1,
        highestResolution = highestResolution
      )
      b ++ inferredDataPoints
    }
  }
  def getResolution(h3Index: HexIndex): Int = H3Wrapper.getResolution(h3Index)
}
