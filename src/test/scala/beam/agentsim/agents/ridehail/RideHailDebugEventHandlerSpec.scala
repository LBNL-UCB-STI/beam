package beam.agentsim.agents.ridehail

import beam.agentsim.agents.GenericEventsSpec
import org.scalatest.matchers.should.Matchers

class RideHailDebugEventHandlerSpec extends GenericEventsSpec with Matchers {

  "A RideHail debug handler " must {
    "detect abnormalities " in {
      val debugHandler = new RideHailDebugEventHandler(this.eventManager)

      processHandlers(List(debugHandler))

      val rhAbnorms = debugHandler.collectAbnormalities()

      rhAbnorms shouldBe empty
      //TODO: add value assertions
    }
  }
}
