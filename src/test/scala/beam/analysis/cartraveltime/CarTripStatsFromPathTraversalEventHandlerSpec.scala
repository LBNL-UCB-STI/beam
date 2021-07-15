package beam.analysis.cartraveltime

import beam.agentsim.agents.GenericEventsSpec
import org.matsim.core.controler.events.IterationEndsEvent
import org.scalatest.Assertion

import scala.reflect.io.File

class CarTripStatsFromPathTraversalEventHandlerSpec extends GenericEventsSpec {

  "CarTripStatsFromPathTraversalEventHandler" must {
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

      checkFileExistenceInRoot("AverageCarSpeed.csv")
      checkFileExistenceInRoot("AverageCarSpeed.png")
      checkFileExistenceInRoot("CarTravelTime.csv")
      checkFileExistenceInRoot("CarTravelDistance.csv")
      checkFileExistenceInRoot("CarSpeed.csv")
      checkFileExistenceInRoot("FreeFlowCarSpeed.csv")
      checkFileExistenceInRoot("percentageFreeSpeed.png")

      // If those start to fail, someone changed vehicle types for beamville or `CarTripStatsFromPathTraversalEventHandler` is changed
      checkFileExistenceInIterFolder("CarRideStats.ridehail.csv.gz", 0)
      checkFileExistenceInIterFolder("CarRideStats.personal.csv.gz", 0)
      checkFileExistenceInIterFolder("CarRideStats.cav.csv.gz", 0)

      checkFileExistenceInIterFolder("AverageSpeed.RideHail.png", 0)
      checkFileExistenceInIterFolder("AverageSpeed.Personal.png", 0)
      checkFileExistenceInIterFolder("AverageSpeed.CAV.png", 0)

      checkFileExistenceInIterFolder("AverageSpeedPercentage.RideHail.png", 0)
      checkFileExistenceInIterFolder("AverageSpeedPercentage.Personal.png", 0)
      checkFileExistenceInIterFolder("AverageSpeedPercentage.CAV.png", 0)

    }
  }

  private def checkFileExistenceInRoot(file: String): Unit = {
    val path = beamServices.matsimServices.getControlerIO.getOutputFilename(file)
    require(File(path).isFile, s"$file with full path $path does not exist or it is not a file")
  }

  private def checkFileExistenceInIterFolder(file: String, iteration: Int): Unit = {
    val path = beamServices.matsimServices.getControlerIO.getIterationFilename(iteration, file)
    require(
      File(path).isFile,
      s"$file for iteration $iteration with full path $path does not exist or it is not a file"
    )
  }
}
