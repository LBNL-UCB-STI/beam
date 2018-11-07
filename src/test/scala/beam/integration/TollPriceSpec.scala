package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

class TollPriceSpec extends WordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing tollPrice value" must {
    "create less entries for mode choice car as value increases" ignore {
      val inputTollPrice = Seq(-100.0, 100.0)
      val modeChoice = inputTollPrice.map { tc =>
        new StartWithCustomConfig(
          baseConfig
            .withValue(
              TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
              ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
            )
            .withValue("beam.agentsim.tuning.tollPrice", ConfigValueFactory.fromAnyRef(tc))
        ).groupedCount
      }

      val tc = modeChoice
        .map(_.get("car"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }
}
