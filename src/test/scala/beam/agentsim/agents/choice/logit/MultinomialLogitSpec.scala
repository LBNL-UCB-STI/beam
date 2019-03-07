package beam.agentsim.agents.choice.logit

import beam.sim.BeamHelper
import org.scalatest.{Matchers, WordSpecLike}

import scala.util.Random

/**
  * BEAM
  */
class MultinomialLogitSpec extends WordSpecLike with Matchers with BeamHelper {

  "An MNL Model" must {
    val mnlData: Vector[MnlData] = Vector(
      new MnlData("COMMON", "cost", "multiplier", -0.01),
      new MnlData("COMMON", "time", "multiplier", -0.02),
      new MnlData("car", "intercept", "intercept", 3.0),
      new MnlData("walk", "intercept", "intercept", 4.0)
    )
    val mnl = MultinomialLogit(mnlData)
    val rand = new Random()
    val alts = Vector(
      AlternativeAttributes("car", Map("cost"  -> 30.0, "time" -> 50.0)),
      AlternativeAttributes("walk", Map("cost" -> 0.0, "time"  -> 40.0))
    )

    "should evaluate utility functions as expected" in {
      val util = mnl.getUtilityOfAlternative(alts(0))
      util should equal(1.7)
    }
    "should evaluate expected max utility as expected" in {
      val util = mnl.getExpectedMaximumUtility(alts)
      Math.abs(util - 3.401413) < 0.00001 should be(true)
    }
    "should sample higher probability alternatives more often" in {
      // With these inputs, we expect "walk" ~81% of the time, which translates to an almost certainty that majority
      // will be walk with 100 trials (p-val 3.00491e-12)

      val samps = for (i <- 1 until 100) yield mnl.sampleAlternative(alts, rand).get

      samps.count(_.equals("walk")) > 50 should be(true)
    }
  }
}
