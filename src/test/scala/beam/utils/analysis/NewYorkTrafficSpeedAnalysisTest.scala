package beam.utils.analysis

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, LineString}
import org.scalatest.FunSuite

/**
  *
  * @author Dmitry Openkov
  */
class NewYorkTrafficSpeedAnalysisTest extends FunSuite {
  val geometryFactory: GeometryFactory = new GeometryFactory()

  test("testMatchByDirection") {
    val candidates = List(
      lineString(0, 0, 10, 20),
      lineString(-4, -10, 10, 20),
      lineString(-40, -20, -100, 20),
      lineString(-0, -20, -100, -10)
    )
    assertResult(lineString(-4, -10, 10, 20)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(1, 0, 2, 3), candidates)
    }
    assertResult(lineString(-40, -20, -100, 20)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(1, 0, -2, 3), candidates)
    }
    assertResult(lineString(0, -20, -100, -10)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(1, 0, -2, 1), candidates)
    }
  }

  test("aroundZero") {
    val candidates = List(
      lineString(0, 0, 1, 20),
      lineString(0, 0, 4, -1),
      lineString(0, 0, -1, 20),
      lineString(0, 0, -4, -1),
      lineString(0, 0, -4, 1)
    )
    assertResult(lineString(0, 0, 4, -1)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(0, 0, 2, 0.1), candidates)
    }
    assertResult(lineString(0, 0, -4, 1)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(0, 0, -2, 0.1), candidates)
    }
    assertResult(lineString(0, 0, -4, -1)) {
      NewYorkTrafficSpeedAnalysis.matchByDirection(lineString(0, 0, -2, -0.1), candidates)
    }
  }

  private def lineString(x0: Double, y0: Double, x1: Double, y1: Double): LineString = {
    geometryFactory.createLineString(Array(new Coordinate(x0, y0), new Coordinate(x1, y1)))
  }
}
