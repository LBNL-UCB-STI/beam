package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object RideHailingWaitingSingleAnalysisObject extends OutputDataDescriptor {

  private val fileName = "rideHailWaitingSingleStats"

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, fileName + ".csv")
    val outputDirPath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          this.getClass.getSimpleName,
          relativePath,
          "WaitingTime(sec)",
          "Time spent by a passenger on waiting for a ride hail"
        )
      )
    list.add(OutputDataDescription(this.getClass.getSimpleName, relativePath, "Hour*", "Hour of the day"))
    list
  }

}
