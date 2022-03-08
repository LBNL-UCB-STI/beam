package beam.utils.osm

import com.conveyal.osmlib.Way
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class WayFixerTest extends AnyWordSpec with Matchers {
  "A WayFixer" should {
    "be able to fix lanes" in {
      {
        val w = new Way()
        w.addTag(WayFixer.LANES_TAG, "3")
        WayFixer.fixLanes(1, w) shouldBe false
        w.getTag(WayFixer.LANES_TAG) should be("3")
      }

      {
        val w = new Way()
        // Average of [3,2] is 2.5. (2.5).toInt = 2
        w.addTag(WayFixer.LANES_TAG, "['3', '2']")
        WayFixer.fixLanes(1, w) shouldBe true
        w.getTag(WayFixer.LANES_TAG) should be("2")
      }
      {
        val w = new Way()
        w.addTag(WayFixer.LANES_TAG, "['3', '1']")
        WayFixer.fixLanes(1, w) shouldBe true
        w.getTag(WayFixer.LANES_TAG) should be("2")
      }
    }

    "be able to fix highway type" in {
      {
        val w = new Way()
        w.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        WayFixer.fixHighwayType(1, w) shouldBe false
        w.getTag(WayFixer.HIGHWAY_TAG) should be("motorway")
      }

      {
        val w = new Way()
        w.addTag(WayFixer.HIGHWAY_TAG, "['motorway', 'trunk']")
        WayFixer.fixHighwayType(1, w) shouldBe true
        w.getTag(WayFixer.HIGHWAY_TAG) should be("motorway")
      }

      {
        val w = new Way()
        w.addTag(WayFixer.HIGHWAY_TAG, "['motorway_link', 'secondary']")
        WayFixer.fixHighwayType(1, w) shouldBe true
        w.getTag(WayFixer.HIGHWAY_TAG) should be("secondary")
      }
    }

    "be able to derive max speed based on existing max speed values (unit: mph)" in {
      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "30 mph")

        w2.addTag(WayFixer.HIGHWAY_TAG, "no-motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "20 mph")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(3, w3)
        // 30 mph == 48.28032 kmph
        w3.getTag(WayFixer.MAXSPEED_TAG) should be("48.28032")
      }

      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "35 mph")

        w2.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "25 mph")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(3, w3)
        // 30 mph == 48.28032 kmph
        w3.getTag(WayFixer.MAXSPEED_TAG) should be("48.28032")
      }
    }
    "be able to derive max speed based on existing max speed values (unit: knots)" in {
      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "30 knots")

        w2.addTag(WayFixer.HIGHWAY_TAG, "no-motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "20 knots")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(3, w3)
        // 30 knots == 55.56 kmph
        w3.getTag(WayFixer.MAXSPEED_TAG) should be("55.56")
      }

      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "35 knots")

        w2.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "25 knots")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(3, w3)
        // 30 knots == 55.56 kmph
        w3.getTag(WayFixer.MAXSPEED_TAG) should be("55.56")
      }
    }
    "be able to derive max speed based on existing max speed values (default unit: kmph)" in {
      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "25")

        w2.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "35")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(3, w3)
        w3.getTag(WayFixer.MAXSPEED_TAG) should be("30.0")
      }

      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()
        val w4 = new Way()
        val w5 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "15 km/h")

        w2.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "25 kph")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w3.addTag(WayFixer.MAXSPEED_TAG, "35 kmph")

        w4.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w4.addTag(WayFixer.MAXSPEED_TAG, "45")

        w5.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3, w4, w5), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(5, w5)
        w5.getTag(WayFixer.MAXSPEED_TAG) should be("30.0")
      }
    }
    "be able to derive max speed based on existing max speed values (mixed units)" in {
      {
        val w1 = new Way()
        val w2 = new Way()
        val w3 = new Way()
        val w4 = new Way()

        w1.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w1.addTag(WayFixer.MAXSPEED_TAG, "30 kmph")

        w2.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w2.addTag(WayFixer.MAXSPEED_TAG, "18.64114 mph")

        w3.addTag(WayFixer.HIGHWAY_TAG, "motorway")
        w3.addTag(WayFixer.MAXSPEED_TAG, "16.19872 knots")

        w4.addTag(WayFixer.HIGHWAY_TAG, "motorway")

        val speedByRoadTypes = OsmSpeedConverter.avgMaxSpeedByRoadType(List(w1, w2, w3, w4), "MEAN")
        WayFixer.deriveMaxSpeedFromRoadType(speedByRoadTypes)(4, w4)
        // 18.64114 mph ~= 30.0 km/h
        // 16.19872 knots ~= 30.0 km/h
        w4.getTag(WayFixer.MAXSPEED_TAG).toDouble should be(30.0 +- 0.00005)
      }

    }
  }
}
