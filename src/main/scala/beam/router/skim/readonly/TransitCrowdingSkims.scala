package beam.router.skim.readonly

import java.math.RoundingMode

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.core.TransitCrowdingSkimmer.{TransitCrowdingSkimmerInternal, TransitCrowdingSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import com.google.common.math.IntMath
import org.apache.commons.math3.stat.descriptive.rank.Percentile
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

/**
  * @author Dmitry Openkov
  */
class TransitCrowdingSkims(vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]) extends AbstractSkimmerReadOnly {

  def getTransitOccupancyLevelForPercentile(trip: EmbodiedBeamTrip, percentile: Double): Double = {
    val occupancyLevels: IndexedSeq[Double] = for {
      transitLeg   <- trip.legs.filter(leg => leg.beamLeg.mode.isTransit)
      transitStops <- transitLeg.beamLeg.travelPath.transitStops.toIndexedSeq
      internal <- getListOfTransitCrowdingInternals(
        transitLeg.beamVehicleId,
        transitLeg.beamVehicleTypeId,
        transitStops.fromIdx,
        transitStops.toIdx
      )
    } yield internal.numberOfPassengers.toDouble / internal.capacity

    if (occupancyLevels.isEmpty) {
      0
    } else {
      val p = new Percentile()
      p.setData(occupancyLevels.toArray)
      p.evaluate(percentile)
    }
  }

  def getListOfTransitCrowdingInternals(
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    fromStopIdx: Int,
    toStopIdx: Int
  ): IndexedSeq[TransitCrowdingSkimmerInternal] = {
    for {
      stopIdx <- fromStopIdx until toStopIdx
      skimValue = getSkimValue(vehicleId, vehicleTypeId, stopIdx)
    } yield skimValue
  }

  private def getSkimValue(
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    fromStopIdx: Int
  ): TransitCrowdingSkimmerInternal = {
    val key = TransitCrowdingSkimmerKey(vehicleId, fromStopIdx)

    def getValueFrom(x: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal]) = {
      x.get(key).asInstanceOf[Option[TransitCrowdingSkimmerInternal]]
    }

    average(
      getValueFrom(aggregatedFromPastSkims),
      pastSkims.get(currentIteration - 1).flatMap(getValueFrom),
      vehicleTypeId
    )
  }

  private def average(
    first: Option[TransitCrowdingSkimmerInternal],
    second: Option[TransitCrowdingSkimmerInternal],
    vehicleTypeId: Id[BeamVehicleType]
  ): TransitCrowdingSkimmerInternal = {
    def averageData(x: TransitCrowdingSkimmerInternal, y: TransitCrowdingSkimmerInternal) = {
      TransitCrowdingSkimmerInternal(
        numberOfPassengers = IntMath.divide(x.numberOfPassengers + y.numberOfPassengers, 2, RoundingMode.HALF_UP),
        capacity = x.capacity,
        iterations = 2
      )
    }

    (first, second) match {
      case (Some(x), None) =>
        TransitCrowdingSkimmerInternal(IntMath.divide(x.numberOfPassengers, 2, RoundingMode.HALF_UP), x.capacity, 2)
      case (None, Some(x)) =>
        TransitCrowdingSkimmerInternal(IntMath.divide(x.numberOfPassengers, 2, RoundingMode.HALF_UP), x.capacity, 2)
      case (None, None) =>
        val capacity = vehicleTypes
          .get(vehicleTypeId)
          .map(t => t.seatingCapacity + t.standingRoomCapacity)
          .getOrElse(20)
        TransitCrowdingSkimmerInternal(0, capacity, 2)
      case (Some(x), Some(y)) => averageData(x, y)
    }
  }
}
