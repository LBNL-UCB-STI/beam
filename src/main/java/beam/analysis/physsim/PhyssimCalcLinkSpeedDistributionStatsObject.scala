package beam.analysis.physsim

import java.util

import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object PhyssimCalcLinkSpeedDistributionStatsObject extends OutputDataDescriptor {

  private val outputAsSpeedUnitFileName = PhyssimCalcLinkSpeedDistributionStats.outputAsSpeedUnitFileName

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val freeSpeedDistOutputFilePath = ioController
      .getIterationFilename(0, outputAsSpeedUnitFileName + ".csv")
    val freeSpeedDistAsPercetnageOutputFilePath = ioController
      .getIterationFilename(0, outputAsSpeedUnitFileName + ".csv")
    val outputDirPath = ioController.getOutputPath
    val freeSpeedDistRelativePath = freeSpeedDistOutputFilePath.replace(outputDirPath, "")
    val freeSpeedDistAsPercetnageRelativePath = freeSpeedDistAsPercetnageOutputFilePath
      .replace(outputDirPath, "")
    val list = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistRelativePath,
          "freeSpeedInMetersPerSecond",
          "The possible full speed at which a vehicle can drive through the given link (in m/s)"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistRelativePath,
          "numberOfLinks",
          "Total number of links in the network that allow vehicles to travel with speeds up to the given free speed"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistAsPercetnageRelativePath,
          "linkEfficiencyInPercentage",
          "Average speed efficiency recorded by the the given network link in a day"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          freeSpeedDistAsPercetnageRelativePath,
          "numberOfLinks",
          "Total number of links having the corresponding link efficiency"
        )
      )
    list
  }

}
