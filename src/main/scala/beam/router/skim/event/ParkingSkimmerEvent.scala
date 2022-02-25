package beam.router.skim.event

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.SkimsUtils
import beam.router.skim.core.ParkingSkimmer.{ChargerType, ParkingSkimmerInternal, ParkingSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey, ParkingSkimmer}
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class ParkingSkimmerEvent(
  val eventTime: Double,
  val tazId: Id[TAZ],
  val chargerType: ChargerType,
  val walkAccessDistanceInM: Double,
  val parkingCostPerHour: Double
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = ParkingSkimmer.name

  override val getKey: AbstractSkimmerKey =
    ParkingSkimmerKey(tazId, SkimsUtils.timeToBin(eventTime.toInt), chargerType)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    ParkingSkimmerInternal(walkAccessDistanceInM, parkingCostPerHour)
}
