package beam.router.skim

import java.math.RoundingMode

import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import com.google.common.math.IntMath
import org.apache.commons.math3.stat.descriptive.rank.Percentile
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  *
  * @author Dmitry Openkov
  */
class TransitCrowdingSkims extends AbstractSkimmerReadOnly {

  def getTransitOccupancyLevelForPercentile(trip: EmbodiedBeamTrip, percentile: Double): Double = {
    val occupancyLevels: IndexedSeq[Double] = for {
      transitLeg   <- trip.legs.filter(leg => leg.beamLeg.mode.isTransit)
      transitStops <- transitLeg.beamLeg.travelPath.transitStops.toIndexedSeq
      internal <- getListOfTransitCrowdingInternals(
        transitLeg.beamVehicleId,
        transitStops.fromIdx,
        transitStops.toIdx
      )
    } yield internal.numberOfPassengers.toDouble / internal.capacity
    val p = new Percentile()

    p.setData(occupancyLevels.toArray)
    p.evaluate(percentile)
  }

  def getListOfTransitCrowdingInternals(
    vehicleId: Id[Vehicle],
    fromStopIdx: Int,
    toStopIdx: Int
  ): IndexedSeq[TransitCrowdingSkimmerInternal] = {
    for {
      stopIdx   <- fromStopIdx until toStopIdx
      skimValue <- getSkimValue(vehicleId, stopIdx)
    } yield skimValue
  }

  private def getSkimValue(vehicleId: Id[Vehicle], fromStopIdx: Int): Option[TransitCrowdingSkimmerInternal] = {
    val key = TransitCrowdingSkimmerKey(vehicleId, fromStopIdx)
    if (pastSkims.size >= 2) {
      average(
        pastSkims(0).get(key).asInstanceOf[Option[TransitCrowdingSkimmerInternal]],
        pastSkims(1).get(key).asInstanceOf[Option[TransitCrowdingSkimmerInternal]]
      ).orElse(getFromPastOrAggregated(key))
    } else
      getFromPastOrAggregated(key)
  }

  private def average(
    first: Option[TransitCrowdingSkimmerInternal],
    second: Option[TransitCrowdingSkimmerInternal]
  ): Option[TransitCrowdingSkimmerInternal] = {
    (first, second) match {
      case (s @ Some(_), None) => s
      case (None, s @ Some(_)) => s
      case (None, None)        => None
      case (Some(prev), Some(current)) =>
        Some(
          TransitCrowdingSkimmerInternal(
            numberOfPassengers =
              IntMath.divide(prev.numberOfPassengers + current.numberOfPassengers, 2, RoundingMode.HALF_UP),
            capacity = IntMath.divide(prev.capacity + current.capacity, 2, RoundingMode.HALF_UP),
            iterations = 2
          )
        )
    }
  }

  private def getFromPastOrAggregated(key: TransitCrowdingSkimmerKey): Option[TransitCrowdingSkimmerInternal] = {
    pastSkims.headOption
      .map(_.get(key))
      .getOrElse(aggregatedSkim.get(key))
      .collect { case x: TransitCrowdingSkimmerInternal => x }
  }
}
