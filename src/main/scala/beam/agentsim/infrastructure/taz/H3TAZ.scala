package beam.agentsim.infrastructure.taz

import java.util

import beam.agentsim.infrastructure.taz.H3TAZ.HexIndex
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.uber.h3core.AreaUnit
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import org.matsim.api.core.v01.population.{Activity, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class H3TAZ(val box: QuadTreeBounds, val scenarioEPSG: String) {
  val h3TazQuadTree: QuadTree[HexIndex] = new QuadTree[HexIndex](box.minx, box.miny, box.maxx, box.maxy)
  val tazToH3TAZMapping: mutable.HashMap[HexIndex, Id[TAZ]] = mutable.HashMap()
  val outTransform: GeotoolsTransformation = new GeotoolsTransformation(H3TAZ.H3Projection, scenarioEPSG)
  val inTransform: GeotoolsTransformation = new GeotoolsTransformation(scenarioEPSG, H3TAZ.H3Projection)

  def getAll: Iterable[HexIndex] = {
    tazToH3TAZMapping.keys
  }

  def getHex(x: Double, y: Double): HexIndex = {
    val coord = inTransform.transform(new Coord(x, y))
    h3TazQuadTree.getClosest(coord.getX, coord.getY)
  }

  def getHexByTAZ(h3TazId: Id[TAZ]): Iterable[HexIndex] = {
    tazToH3TAZMapping.filter(_._2 == h3TazId).keys
  }

  def getTAZ(hex: HexIndex) = {
    tazToH3TAZMapping.getOrElse(H3TAZ.H3.h3ToParentAddress(hex, H3TAZ.defaultResolution), H3TAZ.emptyTAZId)
  }

  def getHexInRadius(x: Double, y: Double, radius: Double): util.Collection[HexIndex] = {
    val coord = inTransform.transform(new Coord(x, y))
    h3TazQuadTree.getDisk(coord.getX, coord.getY, radius)
  }

  def getHexInRadius(loc: Coord, radius: Double): util.Collection[HexIndex] = {
    val transformedCoord = inTransform.transform(loc)
    h3TazQuadTree.getDisk(transformedCoord.getX, transformedCoord.getY, radius)
  }

  private def add(x: Double, y: Double, hex: HexIndex, h3TazId: Id[TAZ] = H3TAZ.emptyTAZId) = {
    h3TazQuadTree.put(x, y, hex)
    tazToH3TAZMapping.put(hex, h3TazId)
  }

}

object H3TAZ {
  type HexIndex = String
  private val H3 = com.uber.h3core.H3Core.newInstance
  val emptyTAZId: Id[TAZ] = Id.create("NA", classOf[TAZ])
  val H3Projection = "EPSG:4326"
  val defaultResolution: Int = 8
  val thresholdResolution: Int = 11 // the hexagon cover an area equivalent to a circle of radius 26m

  def build(scenario: Scenario, tazTreeMap: TAZTreeMap): H3TAZ = {
    val crs = scenario.getConfig.global().getCoordinateSystem
    val in: GeotoolsTransformation = new GeotoolsTransformation(crs, H3Projection)
    val out: GeotoolsTransformation = new GeotoolsTransformation(H3Projection, crs)
    val box = quadTreeExtentFromShapeFile(
      scenario.getNetwork.getNodes.values().asScala.map(n => in.transform(n.getCoord))
    )
    val h3Map = new H3TAZ(box, crs)
    val gf = new GeometryFactory()
    val tazToPolygon = tazTreeMap.getTAZs.map { taz =>
      val boundary = taz.geom.map(in.transform).map(c => new Coordinate(c.getX, c.getY))
      taz.tazId -> gf.createPolygon(boundary :+ boundary.head)
    }.toMap
    fillBox(box, defaultResolution).foreach { hex =>
      val hexCentroid = hexToCoord(hex)
      val selectedTAZ = tazTreeMap
        .getTAZInRadius(out.transform(hexCentroid), 1000)
        .asScala
        .filter { taz =>
          tazToPolygon(taz.tazId).intersects(gf.createPoint(new Coordinate(hexCentroid.getX, hexCentroid.getY)))
        }
        .map(_.tazId)
      h3Map.add(hexCentroid.getX, hexCentroid.getY, hex, selectedTAZ.headOption.getOrElse(emptyTAZId))
    }
    h3Map
  }

  def breakdownByPopulation(scenario: Scenario, granularity: Int, h3Taz: H3TAZ) = {
    val crs = scenario.getConfig.global().getCoordinateSystem
    val in: GeotoolsTransformation = new GeotoolsTransformation(crs, H3Projection)
    val popPerHexMap = scenario.getPopulation.getPersons.asScala
      .map(_._2.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord)
      .map(in.transform)
      .map(home => H3.geoToH3Address(home.getY, home.getX, defaultResolution) -> home)
      .toBuffer
    val popPerHexList = ListBuffer.empty[(HexIndex, String, Double)]
    val hexIndexList = h3Taz.getAll.toBuffer
    while (hexIndexList.nonEmpty) {
      val hex = hexIndexList.remove(0)
      val res = H3.h3GetResolution(hex)
      val count = popPerHexMap.filter(_._1 == hex).map(_._1).groupBy(identity).mapValues(_.size).getOrElse(hex, 0)
      if (count <= granularity || res == thresholdResolution) {
        popPerHexList.append((hex, h3Taz.getTAZ(hex).toString, count))
      } else {
        val subHex = popPerHexMap.filter(_._1 == hex).map {
          case (_, home) => H3.geoToH3Address(home.getY, home.getX, res + 1) -> home
        }
        val allSubHex = H3.h3ToChildren(hex, res + 1).asScala
        val neighborHex = subHex.map(_._1).distinct.filter(!allSubHex.contains(_))
        popPerHexMap.appendAll(subHex)
        //hexIndexList.appendAll(allSubHex ++ neighborHex)
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
    QuadTreeBounds(box.getMinX, box.getMinY, box.getMaxX, box.getMaxY)
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
