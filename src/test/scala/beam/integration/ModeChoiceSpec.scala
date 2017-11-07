package beam.integration

import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class ModeChoiceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  def maxRepetition(listValueTag: Seq[String]): String = {
    val grouped = listValueTag.groupBy(s => s)
    val groupedCount = grouped.map{case (k, v) => (k, v.size)}
    val (maxK, _) = groupedCount.max

    println(s"-----------GroupedCount $groupedCount")

    maxK
  }

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer mode choice car type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val driveIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceDriveIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.get("car").getOrElse(0)
      val driveIfAvailableCount = driveIfAvailableRun.groupedCount.get("car").getOrElse(0)

      driveIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitIfAvailable" must {
    "prefer mode choice transit type than other modes" ignore {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val transitIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceTransitIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.get("transit").getOrElse(0)
      val transitIfAvailableCount = transitIfAvailableRun.groupedCount.get("transit").getOrElse(0)

      transitIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitOnly" must {
    "Generate ModeChoice events file with only transit types" ignore new StartWithCustomConfig(modeChoice = Some("ModeChoiceTransitOnly")){
      listValueTagEventFile.filter(s => s.equals("transit")).size shouldBe listValueTagEventFile.size
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailIfAvailable" must {
    "prefer more mode choice ride hail type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val rideHailIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceRideHailIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.get("ride_hailing").getOrElse(0)
      val rideHailIfAvailableCount = rideHailIfAvailableRun.groupedCount.get("ride_hailing").getOrElse(0)

      rideHailIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceMultinomialLogit" must {
    "Generate events file with for ModeChoice" ignore new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit")){
      fail("Unpredictable output to evaluate")
    }
  }

  "Running beam with modeChoiceClass ModeChoiceDriveOnly" must {
      "Generate ModeChoice events file with only car types" in new StartWithCustomConfig(modeChoice = Some("ModeChoiceDriveOnly")){
      listValueTagEventFile.filter(s => s.equals("car")).size shouldBe listValueTagEventFile.size
      
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailOnly" must {
    "Generate ModeChoice events file with only ride hail types" in new StartWithCustomConfig(modeChoice = Some("ModeChoiceRideHailOnly")){
      listValueTagEventFile.filter(_.equals("ride_hailing")).size shouldBe listValueTagEventFile.size
    }
  }


//  Commented out for now as beam is hanging during run
  "Running beam with modeChoiceClass ModeChoiceUniformRandom" must {
    "Generate events file with exactly four ride_hailing type for ModeChoice" ignore new StartWithCustomConfig(modeChoice = Some("ModeChoiceUniformRandom")){
      fail("Unpredictable output to evaluate")
      }
  }
}
