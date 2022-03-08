package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, FERRY, RAIL, SUBWAY}
import beam.router.model.EmbodiedBeamTrip
import org.matsim.api.core.v01.Id

/**
  * TransitFareDefaults
  *
  * If no fare is found, these defaults can be use.
  */
object TransitFareDefaults {

  // USD per boarding
  // Source: http://www.vitalsigns.mtc.ca.gov/transit-cost-effectiveness
  val faresByMode: Map[BeamMode, Double] = Map(
    BUS    -> 0.99,
    SUBWAY -> 3.43,
    FERRY  -> 6.87,
    RAIL   -> 4.52
  )
  val zero: Double = 0

  def estimateTransitFares(alternatives: IndexedSeq[EmbodiedBeamTrip]): IndexedSeq[Double] = {
    alternatives.map { alt =>
      alt.tripClassifier match {
        case theMode: BeamMode if theMode.isTransit && alt.costEstimate.equals(zero) =>
          var vehId = Id.create("dummy", classOf[BeamVehicle])
          var theFare = zero
          alt.legs.foreach { leg =>
            if (leg.beamVehicleId != vehId && faresByMode.contains(leg.beamLeg.mode)) {
              theFare = theFare + faresByMode(leg.beamLeg.mode)
              vehId = leg.beamVehicleId
            }
          }
          theFare

        case _ =>
          zero
      }
    }
  }
}
