package beam.utils.map

import beam.sim.common.GeoUtils
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
    override def localCRS: String = "epsg:26910"
  }

  def main(args: Array[String]): Unit = {
    val pathToNetwork = "C:/repos/beam/test/input/newyork/r5-prod/newyork-simplified.osm.pbf"
    val pathToPlans = "D:/Work/beam/NewYork/results_06-30-2020_01-47-08/plans.csv"


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
    val activities = CsvPlanElementReader.read(pathToPlans).filter(x => x.planElementType.equalsIgnoreCase("activity"))

    val ch = new ConvexHull(allCoords.toArray, geometryFactory).getConvexHull
    val polygon: Polygon = geometryFactory.createPolygon(ch.getCoordinates)
    writePolygon(polygon, "convex_hull.shp")

    val boundingBoxPolygon = geometryFactory.toGeometry(boundingBox).asInstanceOf[Polygon]
    writePolygon(boundingBoxPolygon, "bounding_box.shp")

    val wgsCoords1 = activities.map { activity =>
      val wgsCoord = new Coord(activity.activityLocationX.get, activity.activityLocationY.get)
      wgsCoord
    }
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
