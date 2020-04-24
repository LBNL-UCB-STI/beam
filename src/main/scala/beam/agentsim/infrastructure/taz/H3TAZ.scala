package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.H3TAZ.{fillBox, toCoord, H3, HexIndex}
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
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

case class H3TAZ(network: Network, tazTreeMap: TAZTreeMap, beamConfig: BeamConfig) extends StrictLogging {
  private val toH3CoordSystem =
    new GeotoolsTransformation(beamConfig.matsim.modules.global.coordinateSystem, H3TAZ.H3Projection)
  private val toScenarioCoordSystem =
    new GeotoolsTransformation(H3TAZ.H3Projection, beamConfig.matsim.modules.global.coordinateSystem)

  private val boundingBox: QuadTreeBounds = H3TAZ.quadTreeExtentFromShapeFile(
    network.getNodes.values().asScala.map(n => toH3CoordSystem.transform(n.getCoord))
  )
  private val resolution = beamConfig.beam.router.skim.h3Resolution
  private val fillBoxResult: Iterable[String] =
    ProfilingUtils.timed(s"fillBox for boundingBox $boundingBox with resolution $resolution", x => logger.info(x)) {
      fillBox(boundingBox, resolution)
    }
  logger.info(s"fillBox for boundingBox $boundingBox with resolution $resolution gives ${fillBoxResult.size} elemets")

  private val tazToH3TAZMapping: Map[HexIndex, Id[TAZ]] =
    ProfilingUtils.timed(s"Constructed tazToH3TAZMapping", str => logger.info(str)) {
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

  def getIndex(c: Coord, resolution: Int): HexIndex = {
    val coord = H3TAZ.toGeoCoord(toH3CoordSystem.transform(c))
    H3TAZ.H3.geoToH3Address(coord.lat, coord.lng, resolution)
  }
  def getIndex(c: Coord): HexIndex = getIndex(c, resolution)
  def getResolution: Int = resolution

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
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue
    for (c <- coords) {
      minX = Math.min(minX, c.getX)
      minY = Math.min(minY, c.getY)
      maxX = Math.max(maxX, c.getX)
      maxY = Math.max(maxY, c.getY)
    }
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

}
