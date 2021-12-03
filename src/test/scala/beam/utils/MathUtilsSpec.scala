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
}
