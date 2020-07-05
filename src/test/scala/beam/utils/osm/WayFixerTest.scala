package beam.utils.osm

import com.conveyal.osmlib.Way
import org.scalatest.{Matchers, WordSpec}

class WayFixerTest extends WordSpec with Matchers {
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
  }
}
