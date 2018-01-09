package beam.integration

import java.io.File

import beam.sim.BeamHelper
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */

class ModeChoiceSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll with IntegrationSpecCommon {

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer mode choice car type than other modes" in {
      val theRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceDriveIfAvailable"))
      )
      val testModeCount = theRun.groupedCount.getOrElse("car", 0)
      val otherModesCount = theRun.groupedCount.getOrElse("ride_hailing", 0) +
        theRun.groupedCount.getOrElse("walk_transit", 0) + theRun.groupedCount.getOrElse("drive_transit", 0) +
        theRun.groupedCount.getOrElse("bike", 0)
      testModeCount should be >= otherModesCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitIfAvailable" must {
    "prefer mode choice transit type than other modes" in {
      val theRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceTransitIfAvailable"))
      )
      val testModeCount = theRun.groupedCount.getOrElse("walk_transit", 0) + theRun.groupedCount.getOrElse("drive_transit",0)
      val otherModesCount = theRun.groupedCount.getOrElse("ride_hailing", 0)
        theRun.groupedCount.getOrElse("bike", 0)
      testModeCount should be >= otherModesCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailIfAvailable" must {
    "prefer more mode choice ride hail type than other modes" in {
      val theRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceRideHailIfAvailable"))
      )
      val testModeCount = theRun.groupedCount.getOrElse("ride_hailing", 0)
      val otherModesCount = theRun.groupedCount.getOrElse("car", 0) +
        theRun.groupedCount.getOrElse("walk_transit", 0) + theRun.groupedCount.getOrElse("drive_transit", 0) +
        theRun.groupedCount.getOrElse("bike", 0)
      testModeCount should be >= otherModesCount
    }
  }

}
