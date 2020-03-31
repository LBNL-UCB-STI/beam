package beam.analysis.physsim

import java.util.{ArrayList, List}
import java.util

import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object PhyssimCalcLinkSpeedStatsObject extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val freeSpeedDistOutputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, PhyssimCalcLinkSpeedStats.OUTPUT_FILE_NAME + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val freeSpeedDistRelativePath: String = freeSpeedDistOutputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistRelativePath,
          "Bin",
          "A given time slot within a day"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistRelativePath,
          "AverageLinkSpeed",
          "The average speed at which a vehicle can travel across the network during the given time bin"
        )
      )
    list
  }

}
