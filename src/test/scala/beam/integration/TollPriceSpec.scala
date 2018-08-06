package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class TollPriceSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing tollPrice value" must {
    "create less entries for mode choice car as value increases" in {
      val inputTollPrice = Seq(-100.0, 100.0)
      val modeChoice = inputTollPrice.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
                ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit")
              )
              .withValue("beam.agentsim.tuning.tollPrice",
                         ConfigValueFactory.fromAnyRef(tc))
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("car"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }
}
