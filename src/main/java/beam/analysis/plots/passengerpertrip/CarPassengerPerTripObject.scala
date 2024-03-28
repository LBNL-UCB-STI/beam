package beam.analysis.plots.passengerpertrip

import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

object CarPassengerPerTripObject {

  def iterationPassengerPerTripOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("CarPassengerPerTrip", "passengerPerTrip{Transit Mode}.csv", iterationLevel = true)(
      """
        hours | The hour when this statistic applies
        Number of passengers | Number trips with this number of passengers
        """
    )
}
