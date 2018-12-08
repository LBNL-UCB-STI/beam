package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object PersonTravelTimeAnalysisObject extends OutputDataDescriptor {

  private val fileBaseName = PersonTravelTimeAnalysis.fileBaseName

  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, fileBaseName + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath: String = outputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(getClass.getSimpleName, relativePath, "Mode", "Travel mode chosen"))
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "Hour,*",
          "Average time taken to travel by the chosen mode during the given hour of the day"
        )
      )
    list
  }
}
