package beam.integration

import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class RideHailNumDriversSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with IntegrationSpecCommon {

  "Running beam with modeChoice ModeChoiceRideHailIfAvailable and increasing defaultCostPerMinute value" must {
    "create less entries for mode choice rideHail as value increases" in{
      val numDriversAsFractionOfPopulation = Seq(0.1, 1.0)
      val modeChoice = numDriversAsFractionOfPopulation.map(tc => new StartWithCustomConfig(
        modeChoice = Some("ModeChoiceRideHailIfAvailable"), numDriversAsFractionOfPopulation = Some(tc)).groupedCount)

      val tc = modeChoice
        .map(_.get("ride_hailing"))
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
