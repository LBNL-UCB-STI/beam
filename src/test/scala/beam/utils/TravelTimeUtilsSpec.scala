package beam.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TravelTimeUtilsSpec extends AnyWordSpecLike with Matchers {

  "TravelTimeUtils.clampTravelTimeSeconds" should {
    "clamp signed floating-point residuals to zero" in {
      TravelTimeUtils.clampTravelTimeSeconds(8.881784197001252e-16) shouldBe 8.881784197001252e-16
      TravelTimeUtils.clampTravelTimeSeconds(-8.881784197001252e-16) shouldBe 0.0
    }

    "leave non-negative travel times unchanged" in {
      TravelTimeUtils.clampTravelTimeSeconds(1.5) shouldBe 1.5
      TravelTimeUtils.clampTravelTimeSeconds(0.0) shouldBe 0.0
    }

    "clamp negative travel times to zero" in {
      TravelTimeUtils.clampTravelTimeSeconds(-0.25) shouldBe 0.0
    }
  }
}
