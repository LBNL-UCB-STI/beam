package beam.router.skim.event

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.SkimsUtils
import beam.router.skim.core.FreightSkimmer.{FreightSkimmerInternal, FreightSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey, FreightSkimmer}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class FreightSkimmerEvent(
  eventTime: Double,
  tazId: Id[TAZ],
  numberOfLoadings: Double,
  numberOfUnloadings: Double,
  costPerMile: Double,
  walkAccessDistanceInM: Double,
  parkingCostPerHour: Double
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = FreightSkimmer.name

  override val getKey: AbstractSkimmerKey =
    FreightSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt))

  override val getSkimmerInternal: AbstractSkimmerInternal =
    FreightSkimmerInternal(
      numberOfLoadings,
      numberOfUnloadings,
      costPerMile,
      walkAccessDistanceInM,
      parkingCostPerHour
    )
}
