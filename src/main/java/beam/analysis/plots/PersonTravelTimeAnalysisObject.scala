package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object PersonTravelTimeAnalysisObject extends OutputDataDescriptor {

  private val fileBaseName = PersonTravelTimeAnalysis.fileBaseName

  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val outputFilePath: String = ioController.getIterationFilename(0, fileBaseName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
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
