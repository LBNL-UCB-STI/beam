package beam.sim.termination

import beam.agentsim.events.{RideHailFleetStoredElectricityEvent, RideHailFleetStoredElectricityEventTracker}
import beam.sim.config.BeamConfigHolder
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.TerminationCriterion
import org.matsim.core.events.handler.BasicEventHandler

/**
  * Termination criterion to stop the iterations when the electricity stored in the ride hail fleet at the beginning of
  * the iteration is close to the amount stored at the end.
  *
  * The combination of setting the config parameter beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations to true
  * and selecting this termination criterion enables fixed point iteration for the electricity stored in the ride hail
  * fleet's batteries. After a couple of iterations, the electricity stored in the ride hail fleet at the beginning
  * of the iteration should be close to the amount stored at the end of the iteration. In this case, we say that the
  * fixed point iteration converged. It is worth noting that we target convergence for the total electricity stored in
  * the fleet and not for the electricity stored in each individual vehicle because getting convergence for each
  * individual vehicle is much more challenging.
  *
  * There is no guarantee that the algorithm will converge. Coarsely, fixed point iteration theory tells us that there
  * will be convergence if the mapping from electricity stored at the beginning to electricity stored at the end
  * does not change too abruptly.
  * Hence, we would not expect convergence for a charging scheduling algorithm that behaves very differently in two
  * iterations even if the initial SoC of the batteries is similar. Nonetheless, we expect convergence in cases where the
  * fleet is large (such that the individual changes in the vehicle's daily routines even out) and the charge
  * scheduling algorithm is reasonable (i.e., behaves similarly in two iterations if the initial SoC of the batteries
  * is similar).
  *
  * The lack of convergence in the fixed point iteration also tells us something about how the charge scheduling
  * algorithm would behave in real life. Linking the vehicles' state of charge and location across iterations represents
  * that the vehicles start the day where they ended the previous day and with the same state of charge (for days with
  * very similar demand). Thus, the lack of convergence tells us that the charge scheduling algorithm does not allow the
  * fleet to reach a steady state where the energy that was charged matches the energy that was consumed. Thus, there
  * will be days where more energy is charged than others.
  *
  * @param beamConfigHolder BEAM configuration holder
  * @param eventsManager Events manager
  *
  * @see <a href="http://fourier.eng.hmc.edu/e176/lectures/NM/node17.html">More information on fixed point iteration</a>
  */
class TerminateAtRideHailFleetStoredElectricityConvergence @Inject()(
  beamConfigHolder: BeamConfigHolder,
  eventsManager: EventsManager
) extends TerminationCriterion
    with BasicEventHandler
    with LazyLogging
    with RideHailFleetStoredElectricityEventTracker {
  eventsManager.addHandler(this)

  private val minLastIteration =
    beamConfigHolder.beamConfig.beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.minLastIteration
  private val maxLastIteration =
    beamConfigHolder.beamConfig.beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.maxLastIteration
  private val relativeTolerance =
    beamConfigHolder.beamConfig.beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.relativeTolerance

  assert(minLastIteration <= maxLastIteration, "minLastIteration cannot be larger than maxLastIteration")

  if (!beamConfigHolder.beamConfig.beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations) {
    logger.warn("linkFleetStateAcrossIterations is false. Stored energy convergence is very unlikely.")
  }

  /** Allows iterations to continue before minLastIteration. Then, stops iterations if the relative difference between
    * electricity stored in the ride hail fleet's batteries is less than relativeTolerance.
    * If this does not happen before reaching maxLastIteration, the iteration is stopped.
    *
    * Note: this method is called right before iteration starts.
    *
    * @param iteration Number of the iteration that would begin if returning true.
    * @return true if the iteration should continue, false otherwise.
    */
  override def continueIterations(iteration: Int): Boolean = {
    val lastIteration = iteration - 1

    if (lastIteration < minLastIteration) {
      true
    } else {
      val storedElectricityRelativeDifference = computeStoredElectricityRelativeDifference

      if (storedElectricityRelativeDifference <= relativeTolerance) {
        logger.info(
          f"Stopping iterations, achieved convergence on iteration $lastIteration. Difference: $storedElectricityRelativeDifference, " +
          f"tolerance: $relativeTolerance"
        )
        false
      } else {
        logger.info(
          f"No convergence on iteration $lastIteration. Difference: $storedElectricityRelativeDifference, " +
          f"tolerance: $relativeTolerance"
        )

        if (lastIteration == maxLastIteration) {
          logger.warn(f"Stopping iterations, reached maxLastIteration $maxLastIteration without convergence.")
          false
        } else {
          true
        }
      }
    }
  }

  override def reset(iteration: Int): Unit = {
    super[BasicEventHandler].reset(iteration)
    super[RideHailFleetStoredElectricityEventTracker].reset()
  }

  /**
    * Handle RideHailFleetStoredElectricityEvents to determine the electricity stored in the fleet at the beginning and
    * end of the iteration.
    *
    * @param event Event
    */
  override def handleEvent(event: Event): Unit = {
    event match {
      case rideHailFleetStoredElectricityEvent: RideHailFleetStoredElectricityEvent =>
        handleRideHailFleetStoredElectricityEvent(rideHailFleetStoredElectricityEvent)
      case _ =>
    }
  }
}
