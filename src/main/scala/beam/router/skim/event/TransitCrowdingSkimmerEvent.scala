package beam.router.skim.event

import beam.router.skim.core.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
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
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = transitCrowdingSkimmerConfig.name

  override val getKey: AbstractSkimmerKey = TransitCrowdingSkimmerKey(vehicleId, fromStopIdx)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    TransitCrowdingSkimmerInternal(numberOfPassengers, capacity)
}
