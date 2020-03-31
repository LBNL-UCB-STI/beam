package beam.analysis.cartraveltime

import beam.utils.Statistics
import org.matsim.api.core.v01.Coord

sealed trait CarType {
  override def toString: String = this.getClass.getSimpleName.replace("$", "")
}

object CarType {
  object Personal extends CarType
  object CAV extends CarType
  object RideHail extends CarType
}

case class CarTripStat(
  vehicleId: String,
  travelTime: Double,
  distance: Double,
  freeFlowTravelTime: Double,
  departureTime: Double,
  startCoordWGS: Coord,
  endCoordWGS: Coord
) {
  def speed: Double = if (travelTime == 0.0) Double.NaN else distance / travelTime

  def freeFlowSpeed: Double = if (freeFlowTravelTime == 0.0) Double.NaN else distance / freeFlowTravelTime
}
case class TravelTimeStatistics(stats: Statistics)

object TravelTimeStatistics {

  def apply(rideStats: Seq[CarTripStat]): TravelTimeStatistics = {
    new TravelTimeStatistics(Statistics(rideStats.map(_.travelTime)))
  }
}

case class SpeedStatistics(stats: Statistics)

object SpeedStatistics {

  def apply(rideStats: Seq[CarTripStat]): SpeedStatistics = {
    new SpeedStatistics(Statistics(rideStats.map(_.speed)))
  }
}

case class DistanceStatistics(stats: Statistics)

object DistanceStatistics {

  def apply(rideStats: Seq[CarTripStat]): DistanceStatistics = {
    new DistanceStatistics(Statistics(rideStats.map(_.distance)))
  }
}

case class FreeFlowTravelTimeStatistics(stats: Statistics)

object FreeFlowTravelTimeStatistics {

  def apply(rideStats: Seq[CarTripStat]): FreeFlowTravelTimeStatistics = {
    new FreeFlowTravelTimeStatistics(Statistics(rideStats.map(_.freeFlowTravelTime)))
  }
}

case class FreeFlowSpeedStatistics(stats: Statistics)

object FreeFlowSpeedStatistics {

  def apply(rideStats: Seq[CarTripStat]): FreeFlowSpeedStatistics = {
    new FreeFlowSpeedStatistics(Statistics(rideStats.map(_.freeFlowSpeed)))
  }
}

case class IterationCarTripStats(
  iteration: Int,
  travelTime: TravelTimeStatistics,
  speed: SpeedStatistics,
  distance: DistanceStatistics,
  freeFlowTravelTime: FreeFlowTravelTimeStatistics,
  freeFlowSpeed: FreeFlowSpeedStatistics
)
