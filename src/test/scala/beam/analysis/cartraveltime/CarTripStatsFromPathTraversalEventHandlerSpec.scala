package beam.analysis.cartraveltime
import beam.agentsim.agents.GenericEventsSpec
import org.matsim.core.controler.events.IterationEndsEvent
import org.scalatest.Assertion
import org.scalatest.Matchers._

import scala.reflect.io.File

class CarTripStatsFromPathTraversalEventHandlerSpec extends GenericEventsSpec {

  "CarRideStatsFromPathTraversalEventHandlerSpec" must {
    "write speed statistics files" in {
      val handler = new CarTripStatsFromPathTraversalEventHandler(
        this.networkHelper,
        beamServices.matsimServices.getControlerIO,
        TakeAllTripsTripFilter,
        "",
        treatMismatchAsWarning = true
      )

      processHandlers(List(handler))

      handler.notifyIterationEnds(new IterationEndsEvent(beamServices.matsimServices, 0))

      checkFileExistenceInRoot("averageCarSpeed.csv")
      checkFileExistenceInRoot("averageCarSpeed.png")
      checkFileExistenceInRoot("CarTravelTime.csv")
      checkFileExistenceInRoot("CarTravelDistance.csv")
      checkFileExistenceInRoot("CarSpeed.csv")
      checkFileExistenceInRoot("FreeFlowCarSpeed.csv")

      // If those start to fail, someone changed vehicle types for beamville or `CarTripStatsFromPathTraversalEventHandler` is changed
      checkFileExistenceInIterFolder("CarRideStats.ridehail.csv.gz", 0)
      checkFileExistenceInIterFolder("CarRideStats.personal.csv.gz", 0)
      checkFileExistenceInIterFolder("CarRideStats.cav.csv.gz", 0)

      checkFileExistenceInIterFolder("AverageSpeed.ridehail.png", 0)
      checkFileExistenceInIterFolder("AverageSpeed.personal.png", 0)
      checkFileExistenceInIterFolder("AverageSpeed.cav.png", 0)

      checkFileExistenceInIterFolder("AverageSpeedPercentage.ridehail.png", 0)
      checkFileExistenceInIterFolder("AverageSpeedPercentage.personal.png", 0)
      checkFileExistenceInIterFolder("AverageSpeedPercentage.cav.png", 0)

    }
  }

  private def checkFileExistenceInRoot(file: String): Assertion = {
    File(beamServices.matsimServices.getControlerIO.getOutputFilename(file)).isFile shouldEqual true
  }

  private def checkFileExistenceInIterFolder(file: String, iteration: Int): Assertion = {
    File(beamServices.matsimServices.getControlerIO.getIterationFilename(iteration, file)).isFile shouldEqual true
  }
}
