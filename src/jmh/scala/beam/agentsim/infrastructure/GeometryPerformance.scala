package beam.agentsim.infrastructure

import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Fork, Mode}
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder

import scala.util.Random

/**
  *
  * @author Dmitry Openkov
  */
class GeometryPerformance {
  import GeometryPerformance._

  @Benchmark
  @Fork(value = 1, warmups = 2)
  @BenchmarkMode(Array(Mode.Throughput))
  def contains(): Unit = {
    val point: Point = randomPoint
    clusters.foreach(_.convexHull.contains(point))
  }

  @Benchmark
  @Fork(value = 1, warmups = 2)
  @BenchmarkMode(Array(Mode.Throughput))
  def nearest(): Unit = {
    val point: Point = randomPoint
    tazTreeMap.getTAZ(point.getX, point.getY)
  }

  private def randomPoint = {
    val x = bounds.maxx + rnd.nextDouble() * (bounds.maxx - bounds.minx)
    val y = bounds.maxy + rnd.nextDouble() * (bounds.maxy - bounds.miny)
    val point = gf.createPoint(new Coordinate(x, y))
    point
  }
}

object GeometryPerformance {
  val (tazTreeMap, clusters, bounds) = loadData
  val gf = new GeometryFactory()
  val rnd = new Random(93837)

  def main(args: Array[String]): Unit = {
    println(s"taz number = ${tazTreeMap.tazQuadTree.size()}, cluster number = ${clusters.size}")
    val opt = new OptionsBuilder()
      .include(classOf[GeometryPerformance].getSimpleName)
      .forks(1)
      .build

    new Runner(opt).run
  }

  private def loadData = {
    val tazMap = taz.TAZTreeMap.fromCsv("test/input/sf-bay/taz-centers.csv")
    val (zones, _) = ZonalParkingManager.loadParkingZones(
      "test/input/sf-bay/parking/taz-parking-unlimited-fast-limited-l2-150-baseline.csv",
      "/not_set",
      1.0,
      1.0,
      new Random(18389),
    )

    val clusters: Vector[HierarchicalParkingManager.ParkingCluster] =
      HierarchicalParkingManager.createClusters(tazMap, zones, 16)

    val bounds: QuadTreeBounds = ShapeUtils.quadTreeBounds(tazMap.getTAZs.map(_.coord))

    (tazMap, clusters, increaseBounds(bounds, 0.1))
  }

  private def increaseBounds(bounds: QuadTreeBounds, fraction: Double) = {
    val dX = (bounds.maxx - bounds.minx) * fraction / 2
    val dY = (bounds.maxy - bounds.miny) * fraction / 2
    QuadTreeBounds(bounds.minx - dX, bounds.miny - dY, bounds.maxx + dX, bounds.maxy + dY)
  }
}
