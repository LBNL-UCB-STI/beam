package beam.router

import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode

case class TimeAndCost(time: Option[Int], cost: Option[Double])

trait TravelTimeAndCost {

  def overrideTravelTimeAndCostFor(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode
  ): TimeAndCost
}
