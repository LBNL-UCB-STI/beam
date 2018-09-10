package beam.utils

import org.scalatest.{Matchers, WordSpec}

class StuckFinderHelperSpec extends WordSpec with Matchers {
  "A StuckFinderHelper" when {
    "empty" should {
      "have size 0" in {
        new StuckFinderHelper[Any].size should be(0)
      }
    }
  }
  "A StuckFinderHelper" should {
    "add value" in {
      val s = new StuckFinderHelper[String]
      s.add(1, "1")
      s.size should be(1)

      s.add(2, "2")
      s.size should be(2)
    }
    "not add value" when {
      "value is already added" in {
        val s = new StuckFinderHelper[String]
        s.add(1, "1")
        s.size should be(1)

        s.add(1, "1")
        s.size should be(1)
      }
    }

    "remove oldest value by timestamp" in {
      val s = new StuckFinderHelper[String]
      s.removeOldest should be(None)
      s.add(100, "100")

      val oldest = s.removeOldest()
      s.size should be(0)
      oldest should be(Some(ValueWithTime("100", 100)))

      s.add(300, "300")
      s.add(100, "100")
      s.add(200, "200")

      s.removeOldest() should be(Some(ValueWithTime("100", 100)))
      s.size should be(2)

      s.removeOldest() should be(Some(ValueWithTime("200", 200)))
      s.size should be(1)

      s.removeOldest() should be(Some(ValueWithTime("300", 300)))
      s.size should be(0)

      s.removeOldest() should be(None)
    }

    "remove value by key" in {
      val s = new StuckFinderHelper[String]
      s.removeByKey("Blah") should be(None)

      s.add(100, "100")
      s.removeByKey("100") should be(Some(ValueWithTime("100", 100)))
      s.size should be(0)

      s.add(300, "300")
      s.add(100, "100")
      s.add(200, "200")

      s.removeByKey("300") should be(Some(ValueWithTime("300", 300)))
      s.size should be(2)

      s.removeByKey("100") should be(Some(ValueWithTime("100", 100)))
      s.size should be(1)

      s.removeByKey("200") should be(Some(ValueWithTime("200", 200)))
      s.size should be(0)
    }
  }
}
