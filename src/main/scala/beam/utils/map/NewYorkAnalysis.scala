package beam.utils.map

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import beam.utils.shape.{NoAttributeShapeWriter, ShapeWriter}
import com.conveyal.osmlib.OSM
import com.vividsolutions.jts.algorithm.ConvexHull
import com.vividsolutions.jts.geom.{Coordinate, Envelope, GeometryFactory, Point, Polygon}
import org.matsim.api.core.v01.Coord

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object NewYorkAnalysis {
  private val geometryFactory = new GeometryFactory()

  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:2263"
  }

  def main(args: Array[String]): Unit = {
    val pathToNetwork = "D:/Work/beam/NewYork/input/OSM/newyork-simplified.osm.pbf"
    val pathToPlans = "D:/Work/beam/NewYork/results_06-30-2020_10-36-34/plans.csv"

    val activities = CsvPlanElementReader.read(pathToPlans).filter(x => x.planElementType.equalsIgnoreCase("activity"))

    val coords = activities
      .map { activity =>
        val wgsCoord = new Coord(activity.activityLocationX.get, activity.activityLocationY.get)
        val utm = geoUtils.wgs2Utm(wgsCoord)
        val wgsBack = geoUtils.utm2Wgs(utm)
        val distance = geoUtils.distLatLon2Meters(wgsBack, wgsCoord)
        (distance, wgsCoord, wgsBack)
      }
      .sortBy { case (diff, _, _) => -diff }
    println(s"wgsCoords1: ${coords.length}")

    val csvWriter =
      new CsvWriter("coords.csv", Array("original_x", "original_y", "converted_x", "converted_y", "distance"))
    coords.foreach {
      case (distance, originalWgs, convertedWgs) =>
        csvWriter.write(originalWgs.getX, originalWgs.getY, convertedWgs.getX, convertedWgs.getY, distance)
    }
    csvWriter.close()

    val wgsCoords1 = coords.map(_._2)

    val boundingBox: Envelope = new Envelope()
    val allCoords = ArrayBuffer[Coordinate]()
    val osm = new OSM(null)
    try {
      osm.readFromFile(pathToNetwork)
      osm.nodes.forEach((_, node) => {
        allCoords += new Coordinate(node.getLon, node.getLat)
        boundingBox.expandToInclude(node.getLon, node.getLat)
      })
      println(s"boundingBox: ${boundingBox}")
      println(s"Number of OSM nodes: ${osm.nodes.size()}")
      println(s"Number of OSM ways: ${osm.ways.size()}")
    } finally {
      Try(osm.close())
    }

    val ch = new ConvexHull(allCoords.toArray, geometryFactory).getConvexHull
    val polygon: Polygon = geometryFactory.createPolygon(ch.getCoordinates)
    writePolygon(polygon, "convex_hull.shp")

    val boundingBoxPolygon = geometryFactory.toGeometry(boundingBox).asInstanceOf[Polygon]
    writePolygon(boundingBoxPolygon, "bounding_box.shp")

    showDistribution(boundingBox, wgsCoords1)

    val withinBoundingBox = NoAttributeShapeWriter.worldGeodetic[Point]("withinBoundingBox.shp")
    val outsideBoundingBox = NoAttributeShapeWriter.worldGeodetic[Point]("outsideBoundingBox.shp")
    wgsCoords1.zipWithIndex.foreach {
      case (wgsCoord, idx) =>
        val point = geometryFactory.createPoint(new Coordinate(wgsCoord.getX, wgsCoord.getY))
        if (boundingBox.contains(wgsCoord.getX, wgsCoord.getY)) {
          withinBoundingBox.add(point, idx.toString)
        } else {
          outsideBoundingBox.add(point, idx.toString)
        }
    }
    withinBoundingBox.write()
    outsideBoundingBox.write()
  }

  private def showDistribution(boundingBox: Envelope, wgsCoords: Array[Coord]): Unit = {
    val distribution = wgsCoords
      .map(c => boundingBox.contains(c.getX, c.getY))
      .groupBy(x => x)
      .map { case (x, xs) => x -> xs.length }
    val sum = distribution.values.sum
    println(s"distribution: ${distribution}")
    distribution.foreach {
      case (isWithingBoundingBox, cnt) =>
        val pct = 100 * cnt.toDouble / sum
        println(s"isWithingBoundingBox: $isWithingBoundingBox, cnt: $cnt, percent: $pct")
    }
  }

  def writePolygon(polygon: Polygon, path: String): Try[ShapeWriter.OriginalToPersistedFeatureIdMap] = {
    val shapeWriter = NoAttributeShapeWriter.worldGeodetic[Polygon](path)
    shapeWriter.add(polygon, "1")
    shapeWriter.write()
  }
}
