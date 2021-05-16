package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class TransitPriceSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing transitPrice value" must {
    "create more entries for mode choice transit as value increases" in {
      val inputTransitPrice = Seq(0.1, 1.0)
      val modeChoice = inputTransitPrice.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
                ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
              )
              .withValue("beam.agentsim.tuning.transitPrice", ConfigValueFactory.fromAnyRef(tc))
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("transit"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }

}
