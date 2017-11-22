package beam.integration

import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class TollPriceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceMultinomialLogit and increasing tollPrice value" must {
    "create less entries for mode choice car as value increases" ignore {
      val inputTollPrice = Seq(0.1, 1.0)
      val modeChoice = inputTollPrice.map(tc => new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"), tollPrice = Some(tc)).groupedCount)

      val tc = modeChoice
        .map(_.get("car"))
        .filter(_.isDefined)
        .map(_.get)

      isOrdered(tc)((a, b) => a >= b) shouldBe true
    }
  }


}
