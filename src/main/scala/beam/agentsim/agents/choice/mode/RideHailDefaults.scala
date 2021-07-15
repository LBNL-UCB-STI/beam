package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode.{CAR, RIDE_HAIL, RIDE_HAIL_TRANSIT}
import beam.router.model.EmbodiedBeamTrip

/**
  * RideHailDefaults
  *
  * If no fare is found, these defaults can be use.
  */
object RideHailDefaults {
  val DEFAULT_COST_PER_MILE = 2.00
  private val zero: Double = 0d

  def estimateRideHailCost(alternatives: Seq[EmbodiedBeamTrip]): Seq[Double] = {
    alternatives.map { alt =>
      alt.tripClassifier match {
        case RIDE_HAIL if alt.costEstimate.equals(zero) =>
          val cost = alt.legs.view
            .filter(_.beamLeg.mode == CAR)
            .map(_.beamLeg.travelPath.distanceInM)
            .sum * DEFAULT_COST_PER_MILE / 1607
          cost
        case RIDE_HAIL_TRANSIT if alt.costEstimate.equals(zero) =>
          val cost = alt.legs.view
            .filter(_.beamLeg.mode == CAR)
            .map(_.beamLeg.travelPath.distanceInM)
            .sum * DEFAULT_COST_PER_MILE / 1607
          cost
        case _ =>
          zero
      }
    }
  }

}
