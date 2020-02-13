package beam.analysis.cartraveltime
import beam.agentsim.agents.GenericEventsSpec
import org.scalatest.Matchers._

import scala.reflect.io.File

class CarRideStatsFromPathTraversalEventHandlerSpec extends GenericEventsSpec {

  "CarRideStatsFromPathTraversalEventHandlerSpec" must {
    "write speed statistics files" in {
      val handler = new CarRideStatsFromPathTraversalEventHandler(
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
    }
  }

  private def checkFileExistenceInRoot(file: String) = {
    File(beamServices.matsimServices.getControlerIO.getOutputFilename(file)).isFile shouldEqual true
  }
}
