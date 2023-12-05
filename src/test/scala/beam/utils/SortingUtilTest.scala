package beam.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
  * @author Dmitry Openkov
  */
class SortingUtilTest extends AnyWordSpec with Matchers {
  "sortAsIntegers" should {
    "sort strings by their integer representation" in {
      SortingUtil.sortAsIntegers(Seq("0003", "01", "-03")).get shouldBe Seq("-03", "01", "0003")
      SortingUtil.sortAsIntegers(Seq(
      "060014251042",
      "060014017003",
      "060014233003",
      "060014022003",
      "060014017001",
      )).get shouldBe Seq(
        "060014017001",
        "060014017003",
        "060014022003",
        "060014233003",
        "060014251042",
      )
    }

    "return a None in case of parse error" in {
      SortingUtil.sortAsIntegers(Seq("0", "x")) shouldBe None
    }
  }
}
