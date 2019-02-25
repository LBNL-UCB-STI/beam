package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object RideHailRevenueAnalysisObject extends OutputDataDescriptor {

  private val fileBaseName = RideHailRevenueAnalysis.fileBaseName

  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getOutputFilename(fileBaseName + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath: String = outputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(getClass.getSimpleName, relativePath, "iteration #", "iteration number"))
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "revenue",
          "Revenue generated from ride hail"
        )
      )
    list
  }

}
