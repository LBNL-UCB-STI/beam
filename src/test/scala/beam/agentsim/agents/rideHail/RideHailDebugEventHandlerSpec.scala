package beam.agentsim.agents.rideHail

import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.scalatest.{Matchers, WordSpecLike}

class RideHailDebugEventHandlerSpec extends WordSpecLike with Matchers {


  "A RideHail debug handler " must {
    "detect abnormalities " in {
      val events = EventsUtils.createEventsManager
      val debugHandler = new RideHailDebugEventHandler(events)


      val reader = new MatsimEventsReader(events)

      reader.readFile("test/input/beamville/test-data/beamville.events.xml")
//      reader.readFile("output/beamville/beamville__2018-06-09_05-57-41/ITERS/it.1/1.events.xml")

      debugHandler.reset(0)


      //TODO: add value assertions
    }
  }
}
