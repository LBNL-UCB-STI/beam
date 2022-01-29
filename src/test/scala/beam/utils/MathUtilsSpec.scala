package beam.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class MathUtilsSpec extends AnyWordSpecLike with Matchers {
  "round uniformly" when {
    "receive a positive double value" should {
      "round it to the closest integer most of the time (proportionally to the fraction)" in {
        val rnd = new Random(1777)
        val rounded = (1 to 1000) map (_ => MathUtils.roundUniformly(1.1, rnd))
        rounded.count(_ == 1) should be > 880
        rounded.count(_ == 2) should be < 920
      }
    }
    "receive a negative double value" should {
      "round it to the closest integer most of the time (proportionally to the fraction)" in {
        val rnd = new Random(6134444)
        val rounded = (1 to 1000) map (_ => MathUtils.roundUniformly(-1.65, rnd))
        rounded.count(_ == -1) should be < 400
        rounded.count(_ == -2) should be > 600
      }
    }
    "receive integer values" should {
      "round it to the same integer" in {
        MathUtils.roundUniformly(1.0) should be(1)
        MathUtils.roundUniformly(-1.0) should be(-1)
        MathUtils.roundUniformly(0.0) should be(0)
        MathUtils.roundUniformly(-0.0) should be(0)
      }
    }
  }

  "clamp" when {
    "provided with a value lower then the lower bound" should {
      "return low bound" in {
        MathUtils.clamp(-1.2, -1.0, -0.5) shouldBe -1.0
        MathUtils.clamp(0.2, 1.0, 2.5) shouldBe 1.0
      }
      "provided with a value greater then the upper bound" should {
        "return upper bound" in {
          MathUtils.clamp(1.2, -1.0, 0.5) shouldBe 0.5
          MathUtils.clamp(300.0, 21.0, 221.5) shouldBe 221.5
        }
      }
      "provided with a value within the range" should {
        "return this value" in {
          MathUtils.clamp(0.0, -1.0, 0.5) shouldBe 0.0
        }
      }
      "provided with a value equals to upper bound" should {
        "return upper bound" in {
          MathUtils.clamp(0.5, -1.0, 0.5) shouldBe 0.5
        }
      }
      "provided with a value equals to lower bound" should {
        "return lower bound" in {
          MathUtils.clamp(11.0, 11.0, 12.5) shouldBe 11.0
        }
      }
    }
  }
}
