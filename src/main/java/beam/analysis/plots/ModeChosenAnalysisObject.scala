package beam.analysis.plots

import java.util
import beam.sim.OutputDataDescription
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import org.matsim.core.controler.OutputDirectoryHierarchy

object ModeChosenAnalysisObject extends OutputDataDescriptor {

  private val modeChoiceFileBaseName = ModeChosenAnalysis.modeChoiceFileBaseName
  private val referenceModeChoiceFileBaseName = ModeChosenAnalysis.referenceModeChoiceFileBaseName

  def iterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ModeChosenAnalysis", s"$modeChoiceFileBaseName.csv", iterationLevel = true)(
      """
        Modes | Beam mode
        Bin_N | Number of choices of this mode in bin number N (usually bin interval is an hour)
        """
    )

  def activitySimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ModeChosenAnalysis", s"${modeChoiceFileBaseName}_commute.csv")(
      """
        iterations            | iteration number
        car                   | Number of car commutes
        drive_transit         | Number of drive transit commutes
        ride_hail             | Number of rid-hail commutes
        ride_hail_transit     | Number of rid-hail transit commutes
        walk                  | Number of walk commutes
        bike                  | Number of bike commutes
        bike_transit          | Number of bike transit commutes
        """
    )

  def activitySimReferenceOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ModeChosenAnalysis", s"${referenceModeChoiceFileBaseName}_commute.csv")(
      """
        iterations            | iteration number or "benchmark" which means the benchmark row
        car                   | Share of car commutes (%)
        drive_transit         | Share of drive transit commutes (%)
        ride_hail             | Share of rid-hail commutes (%)
        ride_hail_transit     | Share of rid-hail transit commutes (%)
        walk                  | Share of walk commutes (%)
        bike                  | Share of bike commutes (%)
        bike_transit          | Share of bike transit commutes (%)
        """
    )

  def iterationAlternativesCount: OutputDataDescriptor =
    OutputDataDescriptorObject(
      "ModeChosenAnalysis",
      s"modeChosenAvailableAlternativesCount.csv",
      iterationLevel = true
    )(
      """
        modeChosen            | Chosen mode            
        alternativesAvailable | Available alternatives                       
        numberOfTimes         | How many times this mode was chosen among these alternatives               
      """
    )

  def iterationActivitySimAlternativesCount: OutputDataDescriptor =
    OutputDataDescriptorObject(
      "ModeChosenAnalysis",
      s"modeChosenAvailableAlternativesCount_commute.csv",
      iterationLevel = true
    )(
      """
        modeChosen            | Chosen mode (during commute trips)
        alternativesAvailable | Available alternatives
        numberOfTimes         | How many times this mode was chosen among these alternatives
      """
    )

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
          modeChoiceRelativePath,
          "bike_transit",
          "Bike to transit chosen as travel mode"
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          modeChoiceRelativePath,
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
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          referenceModeChoiceRelativePath,
          "bike_transit",
          "Bike to transit chosen as travel mode"
        )
      )
    list
  }

}
