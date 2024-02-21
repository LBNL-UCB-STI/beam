package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import org.matsim.core.controler.OutputDirectoryHierarchy

object RealizedModeAnalysisObject extends OutputDataDescriptor {

  private val fileName = RealizedModeAnalysis.defaultFileName

  def activitySimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", s"realizedModeChoice_commute.csv")(
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

  def referenceOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", s"referenceRealizedModeChoice.csv")(
      """
        iterations            | iteration number or "benchmark" which means the benchmark row
        car                   | Share of car trips (%)
        drive_transit         | Share of drive transit trips (%)
        ride_hail             | Share of rid-hail trips (%)
        ride_hail_transit     | Share of rid-hail transit trips (%)
        walk                  | Share of walk trips (%)
        bike                  | Share of bike trips (%)
        bike_transit          | Share of bike transit trips (%)
        """
    )

  def activitySimReferenceOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", s"referenceRealizedModeChoice_commute.csv")(
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

  def replanningReasonOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", s"replanningEventReason.csv")(
      """
          Mode              | Iteration number
          Problem TourMode  | Number of corresponding problems happened at a tour with TourMode
          """
    )

  def activitySimReplanningReasonOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RealizedModeAnalysis", s"replanningEventReason_commute.csv")(
      """
          Mode              | Iteration number
          Problem TourMode  | Number of corresponding problems happened at a commute tour with TourMode
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
