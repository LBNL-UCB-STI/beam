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
class TransitCapacitySpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceTransitIfAvailable and increasing transitCapacity value" must {
    "create more entries for mode choice transit as value increases" in {
      val inputTransitCapacity = (BigDecimal(0.1) to BigDecimal(1.0) by BigDecimal(0.9)).map(_.toDouble)
      val modeChoice = inputTransitCapacity.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
                ConfigValueFactory.fromAnyRef("ModeChoiceTransitIfAvailable")
              )
              .withValue("beam.agentsim.tuning.transitCapacity", ConfigValueFactory.fromAnyRef(tc))
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get("transit"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a <= b) shouldBe true
    }
  }

}
