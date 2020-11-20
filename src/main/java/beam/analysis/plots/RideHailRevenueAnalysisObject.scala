package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object RideHailRevenueAnalysisObject extends OutputDataDescriptor {

  private val fileBaseName = RideHailRevenueAnalysis.fileBaseName

  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val outputFilePath: String = ioController
      .getOutputFilename(fileBaseName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
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
