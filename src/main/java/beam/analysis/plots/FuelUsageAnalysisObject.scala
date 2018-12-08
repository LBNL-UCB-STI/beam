package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object FuelUsageAnalysisObject extends OutputDataDescriptor {

  private val fileBaseName = FuelUsageAnalysis.fileBaseName

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, fileBaseName + ".csv")
    val outputDirPath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath = outputFilePath.replace(outputDirPath, "")
    val list = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "Modes",
          "Mode of travel chosen by the passenger"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "Bin_*",
          "Energy consumed by the vehicle while travelling by the chosen mode within the given time bin"
        )
      )
    list
  }
}
