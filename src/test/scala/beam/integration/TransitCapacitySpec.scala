package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class TransitCapacitySpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceTransitIfAvailable and increasing transitCapacity value" must {
    "create more entries for mode choice transit as value increases" in {
      val inputTransitCapacity = 0.1 to 1.0 by 0.9
      val modeChoice = inputTransitCapacity.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
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
