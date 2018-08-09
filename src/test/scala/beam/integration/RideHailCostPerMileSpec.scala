package beam.integration

import beam.router.Modes.BeamMode
import beam.sim.BeamHelper
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class RideHailCostPerMileSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing defaultCostPerMile value" must {
    "create less entries for mode choice rideHail as value increases" ignore {
      val inputCostPerMile = Seq(-1000000.0, 1000000.0)
      val modeChoice = inputCostPerMile.map(
        tc =>
          new StartWithCustomConfig(
            baseConfig
              .withValue(
                "beam.agentsim.agents.modalBehaviors.modeChoiceClass",
                ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit")
              )
              .withValue(
                "beam.agentsim.agents.rideHail.defaultCostPerMile",
                ConfigValueFactory.fromAnyRef(tc)
              )
          ).groupedCount
      )

      val tc = modeChoice
        .map(_.get(BeamMode.RIDE_HAIL.value))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }
}
