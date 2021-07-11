package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig, TestConstants}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

class RideHailCostPerMinuteSpec
    extends AnyWordSpecLike
    with Matchers
    with BeamHelper
    with BeforeAndAfterAll
    with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing defaultCostPerMinute value" must {
    "create less entries for mode choice rideHail as value increases" in {
      val inputCostPerMinute = Seq(0.0, 100.0)

      val modeChoice = inputCostPerMinute.map(tc =>
        new StartWithCustomConfig(
          baseConfig
            .withValue(
              TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
              ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
            )
            .withValue(
              "beam.agentsim.agents.rideHail.defaultCostPerMinute",
              ConfigValueFactory.fromAnyRef(tc)
            )
        ).groupedCount
      )
      val tc = modeChoice
        .map(_.get("ride_hail"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }

}
