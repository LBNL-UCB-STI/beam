package beam.analysis.plots

import java.util

import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor

object GraphSurgePricingObject extends OutputDataDescriptor {

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: util.List[OutputDataDescription] = {
    val surgePricingOutputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, "rideHailSurgePriceLevel.csv")
    val revenueOutputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, "rideHailRevenue.csv")
    val surgePricingAndRevenueOutputFilePath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(0, "tazRideHailSurgePriceLevel.csv.gz")
    val outputDirPath: String = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath
    val surgePricingRelativePath: String = surgePricingOutputFilePath.replace(outputDirPath, "")
    val revenueRelativePath: String = revenueOutputFilePath.replace(outputDirPath, "")
    val surgePricingAndRevenueRelativePath: String = surgePricingAndRevenueOutputFilePath.replace(outputDirPath, "")
    val list: util.List[OutputDataDescription] = new util.ArrayList[OutputDataDescription]
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          surgePricingRelativePath,
          "PriceLevel",
          "Travel fare charged by the ride hail in the given hour"
        )
      )
    list
      .add(OutputDataDescription(getClass.getSimpleName, surgePricingRelativePath, "Hour", "Hour of the day"))
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          revenueRelativePath,
          "Revenue",
          "Revenue earned by ride hail in the given hour"
        )
      )
    list.add(OutputDataDescription(getClass.getSimpleName, revenueRelativePath, "Hour", "Hour of the day"))
    list
      .add(
        OutputDataDescription(getClass.getSimpleName, surgePricingAndRevenueRelativePath, "TazId", "TAZ id")
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          surgePricingAndRevenueRelativePath,
          "DataType",
          "Type of data , can be \"priceLevel\" or \"revenue\""
        )
      )
    list
      .add(
        OutputDataDescription(
          getClass.getSimpleName,
          surgePricingAndRevenueRelativePath,
          "Value",
          "Value of the given data type , can indicate either price Level or revenue earned by the ride hail in the given hour"
        )
      )
    list
      .add(OutputDataDescription(getClass.getSimpleName, surgePricingAndRevenueRelativePath, "Hour", "Hour of the day"))
    list
  }

}
