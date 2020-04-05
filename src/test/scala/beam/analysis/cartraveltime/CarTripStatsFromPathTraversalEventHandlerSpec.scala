package beam.analysis.cartraveltime
import beam.agentsim.agents.GenericEventsSpec
import org.scalatest.Assertion
import org.scalatest.Matchers._

import scala.reflect.io.File

class CarTripStatsFromPathTraversalEventHandlerSpec extends GenericEventsSpec {

  "CarRideStatsFromPathTraversalEventHandlerSpec" must {
    "write speed statistics files" in {
      val handler = new CarTripStatsFromPathTraversalEventHandler(
        this.networkHelper,
        Some(beamServices.matsimServices.getControlerIO)
      )

      processHandlers(List(handler))

      checkFileExistenceInRoot("averageCarSpeed.csv")
      checkFileExistenceInRoot("averageCarSpeed.png")
      checkFileExistenceInRoot("CarTravelTime.csv")
      checkFileExistenceInRoot("CarTravelDistance.csv")
      checkFileExistenceInRoot("CarSpeed.csv")
      checkFileExistenceInRoot("FreeFlowCarSpeed.csv")

      // If those start to fail, someone changed vehicle types for beamville or `CarTripStatsFromPathTraversalEventHandler` is changed
      checkFileExistenceInIterFolder("ridehail.CarRideStats.csv.gz", 0)
      checkFileExistenceInIterFolder("personal.CarRideStats.csv.gz", 0)
      checkFileExistenceInIterFolder("cav.CarRideStats.csv.gz", 0)
    }
  }

  private def checkFileExistenceInRoot(file: String): Assertion = {
    File(beamServices.matsimServices.getControlerIO.getOutputFilename(file)).isFile shouldEqual true
  }

  private def checkFileExistenceInIterFolder(file: String, iteration: Int): Assertion = {
    File(beamServices.matsimServices.getControlerIO.getIterationFilename(iteration, file)).isFile shouldEqual true
  }
}
