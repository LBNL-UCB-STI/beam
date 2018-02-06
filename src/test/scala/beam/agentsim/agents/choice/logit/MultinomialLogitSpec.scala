package beam.agentsim.agents.choice.logit

import beam.sim.BeamHelper
import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * BEAM
  */
class MultinomialLogitSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

    "An MNL Model" must {
      val mnlData: Vector[MnlData] = Vector(
        new MnlData("COMMON", "cost", "multiplier", -0.01),
        new MnlData("COMMON", "time", "multiplier", -0.02),
        new MnlData("car", "intercept", "intercept", 3.0),
        new MnlData("walk", "intercept", "intercept", 4.0)
      )
      val mnl = MultinomialLogit(mnlData)

      "should evaluate utility functions as expected" in {
        val util = mnl.getUtilityOfAlternative(AlternativeAttributes("car", Map(("cost" -> 10.0), ("time" -> 20.0))))
        util should equal(2.5)
      }
      "should evaluate expected max utility as expected" in {
        val util = mnl.getExpectedMaximumUtility(Vector(AlternativeAttributes("car", Map(("cost" -> 10.0), ("time" -> 20.0))),
          AlternativeAttributes("walk", Map(("cost" -> 0.0), ("time" -> 40.0))))
        )
        Math.abs(util - 3.603186) < 0.00001 should be(true)
      }
    }
}
