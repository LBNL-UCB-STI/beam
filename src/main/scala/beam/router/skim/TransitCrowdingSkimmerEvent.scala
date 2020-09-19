package beam.router.skim

import beam.router.skim.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  *
  * @author Dmitry Openkov
  */
class TransitCrowdingSkimmerEvent(
  eventTime: Double,
  transitCrowdingSkimmerConfig: beam.sim.config.BeamConfig.Beam.Router.Skim.TransitCrowdingSkimmer,
  vehicleId: Id[Vehicle],
  fromStopIdx: Int,
  toStopIdx: Int,
  numberOfPassengers: Int,
  capacity: Int,
  duration: Int
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName = transitCrowdingSkimmerConfig.name

  override val getKey = TransitCrowdingSkimmerKey(vehicleId, fromStopIdx)

  override val getSkimmerInternal = TransitCrowdingSkimmerInternal(numberOfPassengers, capacity, duration)
}
