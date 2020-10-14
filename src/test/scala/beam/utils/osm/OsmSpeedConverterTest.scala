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
  }
}
