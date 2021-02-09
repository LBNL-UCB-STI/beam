package beam.utils.osm

import org.scalatest.{Matchers, OptionValues, WordSpec}

class OsmSpeedConverterTest extends WordSpec with Matchers with OptionValues {
  "An OsmSpeedConverter" should {
    "be able to convert any OSM supported unit to km/h" in {
      OsmSpeedConverter.osmSpeedUnit2kmph("10").value shouldBe BigDecimal(10)
      OsmSpeedConverter.osmSpeedUnit2kmph("10 km/h").value shouldBe BigDecimal(10)
      OsmSpeedConverter.osmSpeedUnit2kmph("10 kph").value shouldBe BigDecimal(10)
      OsmSpeedConverter.osmSpeedUnit2kmph("10 kmph").value shouldBe BigDecimal(10)
      OsmSpeedConverter.osmSpeedUnit2kmph("10 mph").value shouldBe BigDecimal(16.09344)
      OsmSpeedConverter.osmSpeedUnit2kmph("10 knots").value shouldBe BigDecimal(18.52)
    }
    "not convert unsupported units" in {
      OsmSpeedConverter.osmSpeedUnit2kmph("10 uns") shouldBe None
    }
    "be able to find average values in BigDecimal List" in {
      val list1 = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).map(BigDecimal(_))
      val list2 = List(9, 8, 7, 1, 2, 3, 4, 5, 6).map(BigDecimal(_))
      val list3 = List(2, 1, 1, 1, 5).map(BigDecimal(_))
      val list4 = List(2, 1, 1, 1, 5, 2).map(BigDecimal(_))

      OsmSpeedConverter.averageValue(list1, "MEAN") shouldBe BigDecimal(5.5)
      OsmSpeedConverter.averageValue(list2, "MEAN") shouldBe BigDecimal(5)
      OsmSpeedConverter.averageValue(list3, "MEAN") shouldBe BigDecimal(2)
      OsmSpeedConverter.averageValue(list4, "MEAN") shouldBe BigDecimal(2)

      OsmSpeedConverter.averageValue(list1, "MEDIAN") shouldBe BigDecimal(5.5)
      OsmSpeedConverter.averageValue(list2, "MEDIAN") shouldBe BigDecimal(5)
      OsmSpeedConverter.averageValue(list3, "MEDIAN") shouldBe BigDecimal(1)
      OsmSpeedConverter.averageValue(list4, "MEDIAN") shouldBe BigDecimal(1.5)
    }
    "not find average values for unsupported inference types" in {
      val list1 = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).map(BigDecimal(_))
      an[IllegalArgumentException] should be thrownBy OsmSpeedConverter.averageValue(list1, "MODE")
    }
  }
}
