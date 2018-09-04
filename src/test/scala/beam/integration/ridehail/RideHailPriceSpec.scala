package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig, TestConstants}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

class RideHailPriceSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing rideHailPrice value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val inputRideHailPrice = Seq(0.1, 1.0)
      val modeChoice = inputRideHailPrice.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
                ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
              )
              .withValue("beam.agentsim.tuning.rideHailPrice", ConfigValueFactory.fromAnyRef(tc))
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
