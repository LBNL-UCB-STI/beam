package beam.integration

import java.io.File

import beam.sim.RunBeam
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by fdariasm on 29/08/2017
  *
  */

class ModeChoiceSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll with IntegrationSpecCommon {

  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
    "prefer mode choice car type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceMultinomialLogit"))
      )
      val driveIfAvailableRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceDriveIfAvailable"))
      )

      val multinomialCount = multinomialRun.groupedCount.getOrElse("car", 0)
      val driveIfAvailableCount = driveIfAvailableRun.groupedCount.getOrElse("car", 0)

      driveIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceTransitIfAvailable" must {
    "prefer mode choice transit type than other modes" ignore {
      val config = ConfigFactory.parseFile(new File(configFileName))
      val multinomialRun = new StartWithCustomConfig(
        config.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceMultinomialLogit"))
      )
      val transitIfAvailableRun = new StartWithCustomConfig(
        config.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceTransitIfAvailable"))
      )
      val multinomialCount = multinomialRun.groupedCount.getOrElse("transit", 0)
      val transitIfAvailableCount = transitIfAvailableRun.groupedCount.getOrElse("transit", 0)

      transitIfAvailableCount should be >= multinomialCount
    }
  }

  "Running beam with modeChoiceClass ModeChoiceRideHailIfAvailable" must {
    "prefer more mode choice ride hail type than other modes" in {
      val multinomialRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceMultinomialLogit"))
      )
      val rideHailIfAvailableRun = new StartWithCustomConfig(
        baseConfig.withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef
        ("ModeChoiceRideHailIfAvailable"))
      )
      val multinomialCount = multinomialRun.groupedCount.getOrElse("ride_hailing", 0)
      val rideHailIfAvailableCount = rideHailIfAvailableRun.groupedCount.getOrElse("ride_hailing", 0)

      rideHailIfAvailableCount should be >= multinomialCount
    }
  }

}
