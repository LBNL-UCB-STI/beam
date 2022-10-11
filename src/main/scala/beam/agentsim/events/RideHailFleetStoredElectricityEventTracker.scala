package beam.agentsim.events

import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

/**
  * Track the initial and final [[FleetStoredElectricityEvent]]s in an iteration to determine the electricity
  * stored in the fleet at the beginning and end of the iteration.
  */
trait RideHailFleetStoredElectricityEventTracker extends LazyLogging {
  protected val storedElectricityEventQueue = new ConcurrentLinkedQueue[FleetStoredElectricityEvent]()

  /** Handle RideHailFleetStoredElectricityEvent. */
  def handleFleetStoredElectricityEvent(
    rideHailFleetStoredElectricityEvent: FleetStoredElectricityEvent
  ): Unit = {
    storedElectricityEventQueue.add(rideHailFleetStoredElectricityEvent)
  }

  /** Reset internal state. Should be called after every iteration. */
  def reset(): Unit = {
    storedElectricityEventQueue.clear()
  }

  /** Computes the relative difference between stored electricity at the beginning and end of the iteration. */
  protected def computeStoredElectricityRelativeDifference: Double = {
    val allEvents = storedElectricityEventQueue.asScala.toIndexedSeq
    val (initialEvents, finalEvents) = allEvents.partition(_.getTime == 0.0)

    val initialCountByFleetId = initialEvents.groupBy(_.fleetId).mapValues(_.size)
    val finalCountByFleetId = finalEvents.groupBy(_.fleetId).mapValues(_.size)
    if (allEvents.nonEmpty && initialCountByFleetId == finalCountByFleetId) {
      val storedElectricityDifferenceInJoules =
        finalEvents.map(_.storedElectricityInJoules).sum - initialEvents.map(_.storedElectricityInJoules).sum
      val storageCapacityInJoules = initialEvents.map(_.storageCapacityInJoules).sum
      storedElectricityDifferenceInJoules.abs / storageCapacityInJoules
    } else {
      logger.error(f"Unexpected initialAndFinalFleetStoredElectricityEvent: {}", allEvents)
      Double.NaN
    }
  }
}
