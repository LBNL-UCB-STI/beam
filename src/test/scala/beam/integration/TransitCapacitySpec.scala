package beam.integration

import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class TransitCapacitySpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with IntegrationSpecCommon{

  "Running beam with modeChoice ModeChoiceTransitIfAvailable and increasing transitCapacity value" must {
    "create more entries for mode choice transit as value increases" ignore {
      val inputTransitCapacity = 0.1 to 1.0 by 0.9
      val modeChoice = inputTransitCapacity.map(tc => new StartWithCustomConfig(modeChoice = Some("ModeChoiceTransitIfAvailable"), transitCapacity = Some(tc)).groupedCount)

      val tc = modeChoice
        .map(_.get("transit"))
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

      isOrdered(tc)((a, b) => a <= b) shouldBe true
    }
  }


}
