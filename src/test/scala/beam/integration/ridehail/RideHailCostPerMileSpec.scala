package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

class RideHailCostPerMileSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing defaultCostPerMile value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val inputCostPerMile = Seq(-1000000.0, 1000000.0)
      val modeChoice = inputCostPerMile.map { tc =>
        new StartWithCustomConfig(
          baseConfig
            .withValue(
              RideHailTestHelper.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
              ConfigValueFactory.fromAnyRef(RideHailTestHelper.AGENT_MODE_CHOICE_MULTINOMIAL_LOGIC)
            )
            .withValue(
              RideHailTestHelper.KEY_DEFAULT_COST_PER_MILE,
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
