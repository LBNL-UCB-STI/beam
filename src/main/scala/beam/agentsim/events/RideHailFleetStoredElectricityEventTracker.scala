package beam.agentsim.events

import com.typesafe.scalalogging.LazyLogging

/**
  * Track the first and last [[RideHailFleetStoredElectricityEvent]] in an iteration to determine the electricity
  * stored in the fleet at the beginning and end of the iteration.
  */
trait RideHailFleetStoredElectricityEventTracker extends LazyLogging {
  protected var initialRideHailFleetStoredElectricityEventOpt: Option[RideHailFleetStoredElectricityEvent] = None
  protected var finalRideHailFleetStoredElectricityEventOpt: Option[RideHailFleetStoredElectricityEvent] = None

  /** Handle RideHailFleetStoredElectricityEvent. */
  def handleRideHailFleetStoredElectricityEvent(
    rideHailFleetStoredElectricityEvent: RideHailFleetStoredElectricityEvent
  ): Unit = {
    if (initialRideHailFleetStoredElectricityEventOpt.isEmpty) {
      initialRideHailFleetStoredElectricityEventOpt = Some(rideHailFleetStoredElectricityEvent)
    } else {
      finalRideHailFleetStoredElectricityEventOpt = Some(rideHailFleetStoredElectricityEvent)
    }
  }

  /** Reset internal state. Should be called after every iteration. */
  def reset(): Unit = {
    initialRideHailFleetStoredElectricityEventOpt = None
    finalRideHailFleetStoredElectricityEventOpt = None
  }

  /** Computes the relative difference between stored electricity at the beginning and end of the iteration. */
  protected def computeStoredElectricityRelativeDifference: Double = {
    val initialAndFinalRideHailFleetStoredElectricityEventOpt =
      (initialRideHailFleetStoredElectricityEventOpt, finalRideHailFleetStoredElectricityEventOpt)

    initialAndFinalRideHailFleetStoredElectricityEventOpt match {
      case (Some(initialRideHailFleetStoredElectricityEvent), Some(finalRideHailFleetStoredElectricityEvent)) =>
        val storedElectricityDifferenceInJoules = (finalRideHailFleetStoredElectricityEvent.storedElectricityInJoules
          - initialRideHailFleetStoredElectricityEvent.storedElectricityInJoules)

        val storageCapacityInJoules = initialRideHailFleetStoredElectricityEvent.storageCapacityInJoules

        if (storageCapacityInJoules == 0.0) {
          Double.NaN
        } else {
          (storedElectricityDifferenceInJoules / storageCapacityInJoules).abs
        }

      case _ =>
        logger.error(
          f"Unexpected initialAndFinalRideHailFleetStoredElectricityEventOpt: " +
          f"$initialAndFinalRideHailFleetStoredElectricityEventOpt"
        )

        Double.NaN
    }
  }
}
