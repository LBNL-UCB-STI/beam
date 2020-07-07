package beam.router.skim

import beam.router.skim.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  *
  * @author Dmitry Openkov
  */
class TransitCrowdingSkims extends AbstractSkimmerReadOnly {

  def getMaxPassengerSkim(
    vehicleId: Id[Vehicle],
    fromStopIdx: Int,
    toStopIdx: Int
  ): Option[TransitCrowdingSkimmerInternal] = {
    val skimValues = for {
      stopIdx   <- fromStopIdx until toStopIdx
      skimValue <- getSkimValue(vehicleId, stopIdx, stopIdx + 1)
    } yield skimValue
    val ord = Ordering.by((_: TransitCrowdingSkimmerInternal).numberOfPassengers)
    skimValues.reduceOption(ord.max)
  }

  private def getSkimValue(
    vehicleId: Id[Vehicle],
    fromStopIdx: Int,
    toStopIdx: Int
  ): Option[TransitCrowdingSkimmerInternal] = {
    val key = TransitCrowdingSkimmerKey(vehicleId, fromStopIdx)
    pastSkims.headOption
      .map(_.get(key))
      .getOrElse(aggregatedSkim.get(key))
      .collect { case x: TransitCrowdingSkimmerInternal => x }
  }
}
