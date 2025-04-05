package beam.analysis.plots

import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

object RideHailWaitingTazAnalysisObject {

  def rideHailWaitingTimeOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RideHailWaitingTazAnalysis", "rideHailWaitingStats.csv", iterationLevel = true)(
      """
        timeBin               | Time bin
        TAZ                   | TAZ id
        avgWait               | Average ride-hail waiting linkStartTime (how long a person waits for a ride-hail vehicle)
        medianWait            | Median ride-hail waiting linkStartTime
        numberOfPickups       | Number of pickups
        avgPoolingDelay       | Always zero
        numberOfPooledPickups | Always zero
        """
    )
}
