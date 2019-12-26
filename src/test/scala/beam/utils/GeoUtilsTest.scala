package beam.utils

import beam.sim.common.GeoUtils
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.{FunSuite, Matchers}

class GeoUtilsTest extends FunSuite with Matchers {
  implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(0.01)

  test("distFormula should work properly") {
    GeoUtils.distFormula(x1 = 0, y1 = 0, x2 = 0, y2 = 0) should equal(0.0)
    GeoUtils.distFormula(x1 = 1, y1 = 1, x2 = 2, y2 = 2) should equal(1.414214)
    GeoUtils.distFormula(x1 = 1, y1 = 3, x2 = 4, y2 = 7) should equal(5.0)
  }
}
