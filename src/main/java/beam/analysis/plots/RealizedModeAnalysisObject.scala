package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object RealizedModeAnalysisObject extends OutputDataDescriptor {

  private val fileName = RealizedModeAnalysis.defaultFileName

  def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val outputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(fileName + ".csv")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val relativePath: String = outputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list.add(OutputDataDescription(getClass.getSimpleName, relativePath, "car", "Car chosen as travel mode"))
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "drive_transit",
          "Drive to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "other",
          "Other modes of travel chosen"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "ride_hail",
          "Ride Hail chosen as travel mode"
        )
      )
    list.add(OutputDataDescription(getClass.getSimpleName, relativePath, "walk", "Walk chosen as travel mode"))
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "walk_transit",
          "Walk to transit chosen as travel mode"
        )
      )
    list
  }

}
