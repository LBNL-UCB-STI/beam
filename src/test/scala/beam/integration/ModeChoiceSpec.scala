package beam.integration

import beam.sim.BeamHelper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * Created by fdariasm on 29/08/2017
  *
  */
class ModeChoiceSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

//  "Running beam with modeChoiceClass ModeChoiceDriveIfAvailable" must {
//    "prefer mode choice car type than other modes" in {
//      val theRun = new StartWithCustomConfig(
//        baseConfig.withValue(TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS, ConfigValueFactory.fromAnyRef
//        ("ModeChoiceDriveIfAvailable"))
//      )
//      val testModeCount = theRun.groupedCount.getOrElse("car", 0)
//      val otherModesCount = theRun.groupedCount.getOrElse("ride_hail", 0) +
//        theRun.groupedCount.getOrElse("walk_transit", 0) + theRun.groupedCount.getOrElse("drive_transit", 0) +
//        theRun.groupedCount.getOrElse("bike", 0)
//      testModeCount should be >= otherModesCount
//    }
//  }

}
