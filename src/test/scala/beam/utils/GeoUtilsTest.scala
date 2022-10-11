package beam.utils

import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Coord
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GeoUtilsTest extends AnyFunSuite with Matchers {
  implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(0.01)

  test("distFormula should work properly") {
    GeoUtils.distFormula(x1 = 0, y1 = 0, x2 = 0, y2 = 0) should equal(0.0)
    GeoUtils.distFormula(x1 = 1, y1 = 1, x2 = 2, y2 = 2) should equal(1.414214)
    GeoUtils.distFormula(x1 = 1, y1 = 3, x2 = 4, y2 = 7) should equal(5.0)
  }

  test("isPointWithinCircle should work properly") {
    GeoUtils.isPointWithinCircle(new Coord(1, 2), 2 * 2, new Coord(0, 0)) shouldBe false
    GeoUtils.isPointWithinCircle(new Coord(1, 2), 2.3 * 2.3, new Coord(0, 0)) shouldBe true
  }

  test("segmentCircleIntersection should work properly") {
    val i1 = GeoUtils.segmentCircleIntersection(new Coord(1, 0), 3, new Coord(2, 1))
    i1.getX shouldBe 3.121 +- 0.001
    i1.getY shouldBe 2.121 +- 0.001
    val i2 = GeoUtils.segmentCircleIntersection(new Coord(1, 0), 3, new Coord(-5, -6))
    i2.getX shouldBe -1.121 +- 0.001
    i2.getY shouldBe -2.121 +- 0.001
    val i3 = GeoUtils.segmentCircleIntersection(new Coord(1, 0), 3, new Coord(6, -5))
    i3.getX shouldBe 3.121 +- 0.001
    i3.getY shouldBe -2.121 +- 0.001
    val i4 = GeoUtils.segmentCircleIntersection(new Coord(2, 4), 4, new Coord(-4, 10))
    i4.getX shouldBe -0.828 +- 0.001
    i4.getY shouldBe 6.828 +- 0.001
  }
}
