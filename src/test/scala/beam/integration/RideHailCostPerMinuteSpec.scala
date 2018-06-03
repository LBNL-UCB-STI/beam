package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */

class RideHailCostPerMinuteSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing defaultCostPerMinute value" must {
    "create less entries for mode choice rideHail as value increases" in {
      val inputCostPerMinute = Seq(0.0, 100.0)
      val modeChoice = inputCostPerMinute.map(tc => new StartWithCustomConfig(
        baseConfig
          .withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit"))
          .withValue("beam.agentsim.agents.rideHailing.defaultCostPerMinute", ConfigValueFactory.fromAnyRef(tc))
      ).groupedCount)

      val tc: Seq[Int] = modeChoice.flatMap(_.get("ride_hailing"))

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }


}
