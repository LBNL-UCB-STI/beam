package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig, TestConstants}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class RideHailCostPerMileSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing defaultCostPerMile value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val inputCostPerMile = Seq(-1000000.0, 1000000.0)
      val modeChoice = inputCostPerMile.map { tc =>
        new StartWithCustomConfig(
          baseConfig
            .withValue(
              TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
              ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
            )
            .withValue(
              "beam.agentsim.agents.rideHail.defaultCostPerMile",
              ConfigValueFactory.fromAnyRef(tc)
            )
        ).groupedCount
      }

      val tc = modeChoice
        .map(_.get("ride_hail"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }
}
