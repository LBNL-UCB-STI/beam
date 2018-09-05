package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class RideHailPriceSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {
  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing rideHailPrice value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val inputRideHailPrice = Seq(0.1, 1.0)
      val modeChoice = inputRideHailPrice.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                RideHailTestHelper.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
                ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit")
              )
              .withValue("beam.agentsim.tuning.rideHailPrice", ConfigValueFactory.fromAnyRef(tc))
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("ride_hail"))
        .filter(_.isDefined)
        .map(_.get)

      //      val z1 = tc.drop(1)
      //      val z2 = tc.dropRight(1)
      //      val zip = z2 zip z1

      //      println("Transit")
      //      println(tc)
      //      println(z1)
      //      println(z2)
      //      println(zip)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }

}
