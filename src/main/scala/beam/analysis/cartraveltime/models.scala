package beam.analysis.cartraveltime

import beam.utils.Statistics
import org.matsim.api.core.v01.Coord

sealed trait CarType extends Comparable[CarType] {
  override def toString: String = this.getClass.getSimpleName.replace("$", "")

  override def compareTo(o: CarType): Int = CarType.compare(this, o)
}

object CarType {

  object Personal extends CarType

  object CAV extends CarType

  object RideHail extends CarType

  def rank(ct: CarType): Int = ct match {
    case Personal => 0
    case CAV      => 1
    case RideHail => 2
  }

  private def compare(a: CarType, b: CarType): Int = rank(a) - rank(b)
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
  def speed: Double = if (travelTime.equals(0d)) Double.NaN else distance / travelTime

  def freeFlowSpeed: Double = if (freeFlowTravelTime.equals(0d)) Double.NaN else distance / freeFlowTravelTime
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

case class WeightedSpeedStatistics(stat: Statistics)

object WeightedSpeedStatistics {

  def apply(rideStats: Seq[CarTripStat]): SpeedStatistics = {
    new SpeedStatistics(Statistics(rideStats.map(_.speed), rideStats.map(_.distance)))
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
