package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object RideHailWaitingAnalysisObject extends OutputDataDescriptor {

  private val fileName = RideHailWaitingAnalysis.fileName

  private val rideHailIndividualWaitingTimesFileBaseName =
    RideHailWaitingAnalysis.rideHailIndividualWaitingTimesFileBaseName

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val rideHailWaitingStatsOutputFilePath: String = ioController.getIterationFilename(0, fileName + ".csv")
    val rideHailIndividualWaitingTimesOutputFilePath: String = ioController
      .getIterationFilename(0, rideHailIndividualWaitingTimesFileBaseName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
    val rideHailWaitingStatsRelativePath: String = rideHailWaitingStatsOutputFilePath.replace(outputDirPath, "")
    val rideHailIndividualWaitingTimesRelativePath: String = rideHailIndividualWaitingTimesOutputFilePath
      .replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailWaitingStatsRelativePath,
          "Waiting Time",
          "The time spent by a passenger waiting for a ride hail"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailWaitingStatsRelativePath,
          "Hour",
          "Hour of the day"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailWaitingStatsRelativePath,
          "Count",
          "Frequencies of times spent waiting for a ride hail during the entire day"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailIndividualWaitingTimesRelativePath,
          "timeOfDayInSeconds",
          "Time of a day in seconds"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailIndividualWaitingTimesRelativePath,
          "personId",
          "Unique id of the passenger travelling by the ride hail"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailIndividualWaitingTimesRelativePath,
          "rideHailVehicleId",
          "Unique id of the ride hail vehicle"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          rideHailIndividualWaitingTimesRelativePath,
          "waitingTimeInSeconds",
          "Time spent by the given passenger waiting for the arrival of the given ride hailing vehicle"
        )
      )
    list
  }

}
