package beam.analysis.plots

import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

object RideHailWaitingTazAnalysisObject {

  def rideHailWaitingTimeOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RideHailWaitingTazAnalysis", "rideHailWaitingStats.csv", iterationLevel = true)(
      """
        timeBin               | Time bin
        TAZ                   | TAZ id
        avgWait               | Average ride-hail waiting time (how long a person waits for a ride-hail vehicle)
        medianWait            | Median ride-hail waiting time
        numberOfPickups       | Number of pickups
        avgPoolingDelay       | Always zero
        numberOfPooledPickups | Always zero
        """
    )
}
