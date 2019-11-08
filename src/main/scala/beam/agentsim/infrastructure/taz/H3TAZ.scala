package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.H3TAZ.{fillBox, HexIndex}
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class H3TAZ(network: Network, tazTreeMap: TAZTreeMap, beamConfig: BeamConfig) {
  private val transformToH3Proj =
    new GeotoolsTransformation(beamConfig.matsim.modules.global.coordinateSystem, H3TAZ.H3Projection)

  private val boundingBox: QuadTreeBounds = H3TAZ.quadTreeExtentFromShapeFile(
    network.getNodes.values().asScala.map(n => transformToH3Proj.transform(n.getCoord))
  )
  private val resolution = beamConfig.beam.h3.resolution
  private val lowerBoundResolution = beamConfig.beam.h3.lowerBoundResolution
  private val tazToH3TAZMapping: mutable.HashMap[HexIndex, Id[TAZ]] = mutable.HashMap()
  fillBox(boundingBox, resolution).foreach { hex =>
    val hexCentroid = H3TAZ.hexToCoord(hex)
    val hexCentroidBis =
      new GeotoolsTransformation(H3TAZ.H3Projection, beamConfig.matsim.modules.global.coordinateSystem)
        .transform(hexCentroid)
    val tazId = tazTreeMap.getTAZ(hexCentroidBis.getX, hexCentroidBis.getY).tazId
    tazToH3TAZMapping.put(hex, tazId)
  }
  //H3TAZ.writeToShp("output/test/polygons2.shp", tazToH3TAZMapping.map(m => (m._1, m._2.toString, 0.0)))

  def getAll: Iterable[HexIndex] = {
    tazToH3TAZMapping.keys
  }

  def getHRHex(x: Double, y: Double): HexIndex = {
    val coord = H3TAZ.toGeoCoord(transformToH3Proj.transform(new Coord(x, y)))
    H3TAZ.H3.geoToH3Address(coord.lat, coord.lng, resolution)
  }

  def getHRHex(tazId: Id[TAZ]): Iterable[HexIndex] = {
    tazToH3TAZMapping.filter(_._2 == tazId).keys
  }

  def getTAZ(hex: HexIndex): Id[TAZ] = {
    tazToH3TAZMapping.getOrElse(hex, TAZTreeMap.emptyTAZId)
  }

}

object H3TAZ {
  type HexIndex = String
  private val H3 = com.uber.h3core.H3Core.newInstance
  val H3Projection = "EPSG:4326"

  def breakdownByPopulation(scenario: Scenario, hexMap: H3TAZ, granularity: Int) = {
    val popPerHexMap = scenario.getPopulation.getPersons.asScala
      .map(_._2.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord)
      .map(hexMap.transformToH3Proj.transform)
      .map(home => H3.geoToH3Address(home.getY, home.getX, hexMap.lowerBoundResolution) -> home)
      .toBuffer
    val popPerHexList = ListBuffer.empty[(HexIndex, String, Double)]
    val hexIndexList = hexMap.getAll.toBuffer
    while (hexIndexList.nonEmpty) {
      val hex = hexIndexList.remove(0)
      val res = H3.h3GetResolution(hex)
      val count = popPerHexMap.filter(_._1 == hex).map(_._1).groupBy(identity).mapValues(_.size).getOrElse(hex, 0)
      if (count <= granularity || res == hexMap.resolution) {
        popPerHexList.append((hex, hexMap.getTAZ(hex).toString, count))
      } else {
        val subHex = popPerHexMap.filter(_._1 == hex).map {
          case (_, home) => H3.geoToH3Address(home.getY, home.getX, res + 1) -> home
        }
        val allSubHex = H3.h3ToChildren(hex, res + 1).asScala
        popPerHexMap.appendAll(subHex)
        hexIndexList.appendAll(allSubHex)
      }
    }
    popPerHexList
  }

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
  private def hexToCoord(hexAddress: String): Coord = {
    val coordinate = toJtsCoordinate(H3.h3ToGeo(hexAddress))
    new Coord(coordinate.x, coordinate.y)
  }
  private def toJtsCoordinate(in: GeoCoord): com.vividsolutions.jts.geom.Coordinate = {
    new com.vividsolutions.jts.geom.Coordinate(in.lng, in.lat)
  }
  private def toGeoCoord(in: Coord): GeoCoord = {
    new GeoCoord(in.getY, in.getX)
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
