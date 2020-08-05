package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import org.matsim.core.controler.OutputDirectoryHierarchy

object ModeChosenAnalysisObject extends OutputDataDescriptor {

  private val modeChoiceFileBaseName = ModeChosenAnalysis.modeChoiceFileBaseName
  private val referenceModeChoiceFileBaseName = ModeChosenAnalysis.referenceModeChoiceFileBaseName

  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val modeChoiceOutputFilePath: String = ioController
      .getOutputFilename(modeChoiceFileBaseName + ".csv")
    val referenceModeChoiceOutputFilePath: String = ioController
      .getOutputFilename(referenceModeChoiceFileBaseName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
    val modeChoiceRelativePath: String = modeChoiceOutputFilePath.replace(outputDirPath, "")
    val referenceModeChoiceRelativePath: String = referenceModeChoiceOutputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "iterations",
          "iteration number"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "car",
          "Car chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "drive_transit",
          "Drive to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "ride_hail",
          "Ride Hail chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "walk",
          "Walk chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
          "walk_transit",
          "Walk to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "iterations",
          "Bike chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "bike",
          "iteration number"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "car",
          "Car chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "drive_transit",
          "Drive to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "ride_hail",
          "Ride Hail chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "ride_hail_transit",
          "Ride Hail to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "walk",
          "Walk chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "walk_transit",
          "Walk to transit chosen as travel mode"
        )
      )
    list
  }

}
