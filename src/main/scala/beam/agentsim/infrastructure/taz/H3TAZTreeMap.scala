package beam.agentsim.infrastructure.taz

import java.util

import beam.agentsim.infrastructure.taz.H3TAZTreeMap.HexIndex
import beam.utils.H3Utils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory, Point}
import org.matsim.api.core.v01.population.{Activity, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

import scala.collection.JavaConverters._
import scala.collection.mutable

class H3TAZTreeMap(val box: QuadTreeBounds, val scenarioEPSG: String) {
  val h3TazQuadTree: QuadTree[HexIndex] = new QuadTree[HexIndex](box.minx, box.miny, box.maxx, box.maxy)
  val tazToH3TAZMapping: mutable.HashMap[HexIndex, Id[TAZ]] = mutable.HashMap()
  val outTransform: GeotoolsTransformation = new GeotoolsTransformation(H3TAZTreeMap.H3PROJ, scenarioEPSG)
  val inTransform: GeotoolsTransformation = new GeotoolsTransformation(scenarioEPSG, H3TAZTreeMap.H3PROJ)

  def getTAZs: Iterable[HexIndex] = {
    h3TazQuadTree.values().asScala
  }

  def getTAZ(x: Double, y: Double): HexIndex = {
    val coord = inTransform.transform(new Coord(x, y))
    h3TazQuadTree.getClosest(coord.getX, coord.getY)
  }

  def getTAZs(h3TazId: Id[TAZ]): Iterable[HexIndex] = {
    tazToH3TAZMapping.filter(_._2 == h3TazId).keys
  }

  def getTAZInRadius(x: Double, y: Double, radius: Double): util.Collection[HexIndex] = {
    val coord = inTransform.transform(new Coord(x, y))
    h3TazQuadTree.getDisk(coord.getX, coord.getY, radius)
  }

  def getTAZInRadius(loc: Coord, radius: Double): util.Collection[HexIndex] = {
    val transformedCoord = inTransform.transform(loc)
    h3TazQuadTree.getDisk(transformedCoord.getX, transformedCoord.getY, radius)
  }

  private def addTAZ(x: Double, y: Double, hex: HexIndex, h3TazId: Id[TAZ] = H3TAZTreeMap.emptyTAZId) = {
    h3TazQuadTree.put(x, y, hex)
    tazToH3TAZMapping.put(hex, h3TazId)
  }

}

object H3TAZTreeMap {
  type HexIndex = String
  val emptyTAZId = Id.create("NA", classOf[TAZ])
  implicit private val H3 = com.uber.h3core.H3Core.newInstance
  val granularity = 10
  val H3PROJ = "EPSG:4326"
  val defaultH3Resolution = 9

  def getResolution(hex: String): Int = H3.h3GetResolution(hex)

  def build(scenario: Scenario, tazTreeMap: TAZTreeMap): H3TAZTreeMap = {
    val crs = scenario.getConfig.global().getCoordinateSystem
    val inTransform: GeotoolsTransformation = new GeotoolsTransformation(crs, H3PROJ)
    val outTransform: GeotoolsTransformation = new GeotoolsTransformation(H3PROJ, crs)
    val coords = scenario.getNetwork.getNodes.values().asScala.map(n => inTransform.transform(n.getCoord))
    val box = quadTreeExtentFromShapeFile(coords)
    val h3Map = new H3TAZTreeMap(box, crs)
    val geofence = getPointsFromBBox(box.minx, box.miny, box.maxx, box.maxy)
    val points = geofence.map(H3Utils.toGeoCoord).toList.asJava
    val holes = List.empty[java.util.List[GeoCoord]].asJava
    val gf = new GeometryFactory()

    val tazToPolygon = tazTreeMap.getTAZs.map { taz =>
      val boundary = taz.geom.map(inTransform.transform).map(c => new Coordinate(c.getX, c.getY))
      taz.tazId -> gf.createPolygon(boundary :+ boundary.head)
    }.toMap

    H3.polyfillAddress(points, holes, defaultH3Resolution).asScala.foreach { hex =>
      val coord = H3Utils.hexToCoord(hex)
      val selectedTAZ = tazTreeMap
        .getTAZInRadius(outTransform.transform(coord), 1000)
        .asScala
        .filter { taz =>
          tazToPolygon(taz.tazId).intersects(gf.createPoint(new Coordinate(coord.getX, coord.getY)))
        }
        .map(_.tazId)
      h3Map.addTAZ(coord.getX, coord.getY, hex, selectedTAZ.headOption.getOrElse(emptyTAZId))
    }

    h3Map
  }

  def fromTAZs(tazTreeMap: TAZTreeMap, scenarioEPSG: String): H3TAZTreeMap = {
    val inTransform: GeotoolsTransformation = new GeotoolsTransformation(scenarioEPSG, H3PROJ)
    val coords = tazTreeMap.getTAZs.flatMap(_.geom.map(inTransform.transform))
    val box = quadTreeExtentFromShapeFile(coords)
    val h3Map = new H3TAZTreeMap(box, scenarioEPSG)
    tazTreeMap.getTAZs
      .map { taz =>
        val geofence0 = taz.geom.map(inTransform.transform)
        (taz, buildFromGeofence(geofence0, Some(taz)))
      }
      .foreach {
        case (taz, hexs) =>
          hexs.foreach { hex =>
            val coord = H3Utils.hexToCoord(hex)
            h3Map.addTAZ(coord.getX, coord.getY, hex, taz.tazId)
          }
      }
    h3Map
  }

  def fromPopulation(population: Population, scenarioEPSG: String): H3TAZTreeMap = {
    val inTransform: GeotoolsTransformation = new GeotoolsTransformation(scenarioEPSG, H3PROJ)
    val coords = population.getPersons.asScala
      .map(_._2.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord)
      .map(inTransform.transform)
    val box = quadTreeExtentFromShapeFile(coords)
    val h3Map = new H3TAZTreeMap(box, scenarioEPSG)
    val geofence0 = getPointsFromBBox(box.minx, box.miny, box.maxx, box.maxy)
    val gf = new GeometryFactory()
    val hexagons = buildFromGeofence(geofence0, None).map { hexIndex =>
      val boundary: mutable.Buffer[GeoCoord] = H3.h3ToGeoBoundary(hexIndex).asScala
      val hexagon =
        gf.createPolygon(boundary.map(H3Utils.toJtsCoordinate).toArray :+ H3Utils.toJtsCoordinate(boundary.head))
      (hexIndex, hexagon)
    }.toBuffer
    val points = gf.createMultiPoint(coords.map(c => new Coordinate(c.getX, c.getY)).toArray)
    while (hexagons.nonEmpty) {
      val (hex, hexagon) = hexagons.remove(0)
      val pointsSubSet = hexagon.intersection(points).asInstanceOf[Point]
      if (pointsSubSet.getNumPoints > granularity) {
        H3.h3ToChildren(hex, getResolution(hex)).asScala.foreach { subHex =>
          val subBoundary: mutable.Buffer[GeoCoord] = H3.h3ToGeoBoundary(subHex).asScala
          val subHexagon = gf.createPolygon(
            subBoundary.map(H3Utils.toJtsCoordinate).toArray :+ H3Utils.toJtsCoordinate(subBoundary.head)
          )
          hexagons.append((subHex, subHexagon))
        }
      } else {
        val coord = H3Utils.hexToCoord(hex)
        h3Map.addTAZ(coord.getX, coord.getY, hex)
      }
    }
    h3Map
  }

  def toPolygons(h3Map: H3TAZTreeMap) = {
    val gf = new GeometryFactory()
    h3Map.getTAZs.map { h =>
      val boundary = H3.h3ToGeoBoundary(h).asScala
      (h, gf.createPolygon(boundary.map(H3Utils.toJtsCoordinate).toArray :+ H3Utils.toJtsCoordinate(boundary.head)))
    }
  }

  def buildFromGeofence(geofence: Array[Coord], taz: Option[TAZ] = None): Iterable[HexIndex] = {
    import com.uber.h3core.util.GeoCoord

    import scala.collection.JavaConverters._
    val points = geofence.map(H3Utils.toGeoCoord).toList.asJava
    // here we assume that all geo fences do not have holes
    val holes = List.empty[java.util.List[GeoCoord]].asJava
    var (hex, res) = (H3.polyfillAddress(points, holes, 0), 8)
    while (hex.isEmpty && res < 15) {
      res = res + 1
      hex = H3.polyfillAddress(points, holes, res)
      //      val tempHex = H3.polyfillAddress(points, holes, res)
      //      if(!tempHex.isEmpty && taz.isDefined && res < 15) {
      //        val totHexArea = H3.hexArea(res, AreaUnit.m2) * tempHex.size()
      //        if(taz.get.areaInSquareMeters >= totHexArea) {
      //          hex = tempHex
      //        }
      //      } else {
      //        hex = tempHex
      //      }
    }
    if (!hex.isEmpty) {
      val res = H3.h3GetResolution(hex.get(0))
      if (res == 5) {
        println("test")
      }
    }
    hex.asScala
  }

  def buildFromGeofence(geofence: Array[Coord]): Iterable[HexIndex] = {
    import com.uber.h3core.util.GeoCoord

    import scala.collection.JavaConverters._
    val points = geofence.map(H3Utils.toGeoCoord).toList.asJava
    // here we assume that all geo fences do not have holes
    val holes = List.empty[java.util.List[GeoCoord]].asJava
    var (hex, res) = (H3.polyfillAddress(points, holes, 0), 8)
    while (hex.isEmpty && res < 15) {
      res = res + 1
      hex = H3.polyfillAddress(points, holes, res)
      //      val tempHex = H3.polyfillAddress(points, holes, res)
      //      if(!tempHex.isEmpty && taz.isDefined && res < 15) {
      //        val totHexArea = H3.hexArea(res, AreaUnit.m2) * tempHex.size()
      //        if(taz.get.areaInSquareMeters >= totHexArea) {
      //          hex = tempHex
      //        }
      //      } else {
      //        hex = tempHex
      //      }
    }
    if (!hex.isEmpty) {
      val res = H3.h3GetResolution(hex.get(0))
      if (res == 5) {
        println("test")
      }
    }
    hex.asScala
  }

  def quadTreeExtentFromShapeFile(coords: Iterable[Coord]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue
    for (coord <- coords) {
      minX = Math.min(minX, coord.getX)
      minY = Math.min(minY, coord.getY)
      maxX = Math.max(maxX, coord.getX)
      maxY = Math.max(maxY, coord.getY)
    }
    val points = getPointsFromBBox(minX, maxX, minY, maxY)
    val gf = new GeometryFactory()
    val polygon = gf.createPolygon(
      points.map(c => new Coordinate(c.getX, c.getY)) :+ new Coordinate(points.head.getX, points.head.getY)
    )
    val box = polygon.asInstanceOf[Geometry].getEnvelopeInternal
    QuadTreeBounds(box.getMinX, box.getMinY, box.getMaxX, box.getMaxY)
  }

  def getPointsFromBBox(minX: Double, maxX: Double, minY: Double, maxY: Double): Array[Coord] = {
    val pointA = new Coord(minX, minY)
    val pointB = new Coord(minX, maxY)
    val pointC = new Coord(maxX, minY)
    val pointD = new Coord(maxX, maxY)
    Array(pointA, pointB, pointC, pointD)
  }

}

