package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
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

  def iterationNonArrivedOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject(
      "PersonTravelTimeAnalysis",
      "NonArrivedAgentsAtTheEndOfSimulation.csv",
      iterationLevel = true
    )(
      """
        modes | Beam mode of the trip
        count | Number of cases when an agent doesn't arrived to the destination at the end of simulation for whatever reason
        """
    )
}
