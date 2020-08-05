package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object RealizedModeAnalysisObject extends OutputDataDescriptor {

  private val fileName = RealizedModeAnalysis.defaultFileName

  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val outputFilePath: String = ioController.getOutputFilename(fileName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
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
