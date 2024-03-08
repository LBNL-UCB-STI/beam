package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import org.matsim.core.controler.OutputDirectoryHierarchy

object RealizedModeAnalysisObject extends OutputDataDescriptor {

  private val fileName = RealizedModeAnalysis.defaultFileName

  def activitySimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "realizedModeChoice_commute.csv")(
      """
        iterations            | iteration number
        car                   | Number of car commutes
        drive_transit         | Number of drive transit commutes
        ride_hail             | Number of rid-hail commutes
        ride_hail_pooled      | Number of rid-hail pooled trips
        ride_hail_transit     | Number of rid-hail transit commutes
        walk                  | Number of walk commutes
        bike                  | Number of bike commutes
        bike_transit          | Number of bike transit commutes
        """
    )

  def activitySimIterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "realizedMode_commute.csv", iterationLevel = true)(
      """
        hour                  | Hour when these trips happens
        car                   | Number of car commutes
        drive_transit         | Number of drive transit commutes
        ride_hail             | Number of rid-hail commutes
        ride_hail_pooled      | Number of rid-hail pooled trips
        ride_hail_transit     | Number of rid-hail transit commutes
        walk                  | Number of walk commutes
        bike                  | Number of bike commutes
        bike_transit          | Number of bike transit commutes
        """
    )

  def iterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "realizedMode.csv", iterationLevel = true)(
      """
        hour                  | Hour when these trips happens
        car                   | Number of car trips
        drive_transit         | Number of drive transit trips
        ride_hail             | Number of rid-hail trips
        ride_hail_pooled      | Number of rid-hail pooled trips
        ride_hail_transit     | Number of rid-hail transit trips
        walk                  | Number of walk trips
        bike                  | Number of bike trips
        bike_transit          | Number of bike transit trips
        """
    )

  def referenceOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "referenceRealizedModeChoice.csv")(
      """
        iterations            | iteration number or "benchmark" which means the benchmark row
        car                   | Share of car trips (%)
        drive_transit         | Share of drive transit trips (%)
        ride_hail             | Share of rid-hail trips (%)
        ride_hail_pooled      | Number of rid-hail pooled trips
        ride_hail_transit     | Share of rid-hail transit trips (%)
        walk                  | Share of walk trips (%)
        bike                  | Share of bike trips (%)
        bike_transit          | Share of bike transit trips (%)
        """
    )

  def activitySimReferenceOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "referenceRealizedModeChoice_commute.csv")(
      """
        iterations            | iteration number or "benchmark" which means the benchmark row
        car                   | Share of car commutes (%)
        drive_transit         | Share of drive transit commutes (%)
        ride_hail             | Share of rid-hail commutes (%)
        ride_hail_pooled      | Number of rid-hail pooled trips
        ride_hail_transit     | Share of rid-hail transit commutes (%)
        walk                  | Share of walk commutes (%)
        bike                  | Share of bike commutes (%)
        bike_transit          | Share of bike transit commutes (%)
        """
    )

  def replanningCountIterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningCountModeChoice.csv")(
      """
        hour   | Hour of simulation
        count  | Number of replanning events happen at that hour
        """
    )

  def activitySimReplanningCountIterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningCountModeChoice_commute.csv")(
      """
        hour   | Hour of simulation
        count  | Number of replanning events happen during commutes
        """
    )

  def activitySimReplanningReasonOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventReason_commute.csv")(
      """
          Mode              | Iteration number
          Problem TourMode  | Number of corresponding problems happened at a commute tour with TourMode
          """
    )

  def replanningReasonOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventReason.csv")(
      """
            Mode              | Iteration number
            Problem TourMode  | Number of corresponding problems happened at a tour with TourMode
            """
    )

  def replanningChainOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventChain.csv", iterationLevel = true)(
      """
            modeChoiceReplanningEventChain  | List of modes and replanning events
            count                           | Number of this mode/replanning chain happened during the iteration
            """
    )

  def activitySimReplanningChainOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventChain_commute.csv", iterationLevel = true)(
      """
            modeChoiceReplanningEventChain  | List of modes and replanning events happened during commutes
            count                           | Number of this mode/replanning chain happened during the iteration
            """
    )

  def replanningReasonIterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventReason.csv", iterationLevel = true)(
      """
              ReplanningReason  | Replanning reason and tour mode
              Count             | Number of replanning event happens on a tour with this mode and this reason
              """
    )

  def activitySimReplanningReasonIterationOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", "replanningEventReason_commute.csv", iterationLevel = true)(
      """
              ReplanningReason  | Replanning reason and tour mode (only commute trips)
              Count             | Number of replanning event happens on a tour with this mode and this reason
              """
    )

  def getOutputDataDescriptions(ioController: OutputDirectoryHierarchy): util.List[OutputDataDescription] = {
    val outputFilePath: String = ioController.getOutputFilename(fileName + ".csv")
    val outputDirPath: String = ioController.getOutputPath
    val relativePath: String = outputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          relativePath,
          "iterations",
          "iteration number"
        )
      )
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
