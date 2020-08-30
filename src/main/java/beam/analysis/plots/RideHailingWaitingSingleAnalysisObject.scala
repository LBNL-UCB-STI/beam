package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object RideHailingWaitingSingleAnalysisObject extends OutputDataDescriptor {

  private val fileName = "rideHailWaitingSingleStats"

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val outputFilePath = ioController
      .getIterationFilename(0, fileName + ".csv")
    val outputDirPath = ioController.getOutputPath
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
