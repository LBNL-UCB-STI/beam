package beam.integration.ridehail

import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig}
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class RideHailNumDriversSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceRideHailIfAvailable and increasing defaultCostPerMinute value" must {
    "create less entries for mode choice rideHail as value increases" in {
      val numDriversAsFractionOfPopulation = Seq(0.1, 1.0)
      val modeChoice = numDriversAsFractionOfPopulation.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
                ConfigValueFactory.fromAnyRef("ModeChoiceRideHailIfAvailable")
              )
              .withValue(
                "beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation",
                ConfigValueFactory.fromAnyRef(tc)
              )
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("ride_hail"))
        .filter(_.isDefined)
        .map(_.get)

      //      val z1 = tc.drop(1)
      //      val z2 = tc.dropRight(1)
      //      val zip = z2 zip z1

      //      println(tc)
      //      println(z1)
      //      println(z2)
      //      println(zip)

      isOrdered(tc)((a, b) => a <= b) shouldBe true
    }
  }

}
