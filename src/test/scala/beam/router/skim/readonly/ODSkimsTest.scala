package beam.router.skim.readonly

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ODSkimsTest extends AnyFunSuite with Matchers {

  test("testGetHoursForRequest") {
    ODSkims.getHoursForRequest(6, 1, 8) shouldBe Seq(5, 7, 4, 8, 3, 2, 1)
    ODSkims.getHoursForRequest(6, 4, 9) shouldBe Seq(5, 7, 4, 8, 9)
    ODSkims.getHoursForRequest(2, 6, 8) shouldBe Seq(6, 7, 8)
    ODSkims.getHoursForRequest(9, 6, 8) shouldBe Seq(6, 7, 8)
    ODSkims.getHoursForRequest(5, 4, 5) shouldBe Seq(4)
    ODSkims.getHoursForRequest(5, 5, 6) shouldBe Seq(6)
    ODSkims.getHoursForRequest(5, 5, 5) shouldBe Seq()
  }
}
