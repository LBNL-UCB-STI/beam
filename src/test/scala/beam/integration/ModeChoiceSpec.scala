package beam.integration

import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class ModeChoiceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer mode choice car type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val driveIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceDriveIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.getOrElse("car", 0)
      val driveIfAvailableCount = driveIfAvailableRun.groupedCount.getOrElse("car", 0)

      driveIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitIfAvailable" must {
    "prefer mode choice transit type than other modes" ignore {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val transitIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceTransitIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.getOrElse("transit", 0)
      val transitIfAvailableCount = transitIfAvailableRun.groupedCount.getOrElse("transit", 0)

      transitIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailIfAvailable" must {
    "prefer more mode choice ride hail type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit"))
      val rideHailIfAvailableRun = new StartWithCustomConfig(modeChoice = Some("ModeChoiceRideHailIfAvailable"))

      val multinomialCount = multinomialRun.groupedCount.getOrElse("ride_hailing", 0)
      val rideHailIfAvailableCount = rideHailIfAvailableRun.groupedCount.getOrElse("ride_hailing", 0)

      rideHailIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceMultinomialLogit" must {
    "Generate events file with for ModeChoice" ignore new StartWithCustomConfig(modeChoice = Some("ModeChoiceMultinomialLogit")){
      fail("Unpredictable output to evaluate")
    }
  }

  //  Commented out for now as beam is hanging during run
  "Running beam with modeChoiceClass ModeChoiceUniformRandom" must {
    "Generate events file with exactly four ride_hailing type for ModeChoice" ignore new StartWithCustomConfig(modeChoice = Some("ModeChoiceUniformRandom")){
      fail("Unpredictable output to evaluate")
      }
  }
}
