package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig, TestConstants}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

class RideHailNumDriversSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceRideHailIfAvailable and increasing defaultCostPerMinute value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val numDriversAsFractionOfPopulation = Seq(0.1, 1.0)
      val modeChoice = numDriversAsFractionOfPopulation.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
                ConfigValueFactory.fromAnyRef("ModeChoiceRideHailIfAvailable")
              )
              .withValue(
                "beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation",
                ConfigValueFactory.fromAnyRef(tc)
              )
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("ride_hail"))
        .filter(_.isDefined)
        .map(_.get)

      val modeChoiceWithLowFraction = tc.head
      val modeChoiceWithHighFraction = tc.last

      modeChoiceWithHighFraction should be < modeChoiceWithLowFraction

    }
  }

}
