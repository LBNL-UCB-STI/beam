package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, FERRY, RAIL, SUBWAY}
import beam.router.RoutingModel.EmbodiedBeamTrip
import org.matsim.api.core.v01.Id

/**
  * TransitFareDefaults
  *
  * If no fare is found, these defaults can be use.
  */
object TransitFareDefaults {

  def estimateTransitFares(
      alternatives: IndexedSeq[EmbodiedBeamTrip]): IndexedSeq[BigDecimal] = {
    alternatives.map { alt =>
      alt.tripClassifier match {
        case theMode: BeamMode
            if theMode.isTransit && alt.costEstimate == 0.0 =>
          var vehId = Id.createVehicleId("dummy")
          var theFare = BigDecimal(0.0)
          alt.legs.foreach { leg =>
            if (leg.beamVehicleId != vehId && faresByMode.contains(
                  leg.beamLeg.mode)) {
              theFare = theFare + BigDecimal(faresByMode(leg.beamLeg.mode))
              vehId = leg.beamVehicleId
            }
          }
          theFare

        case _ =>
          BigDecimal(0)
      }
    }
  }

  // USD per boarding
  // Source: http://www.vitalsigns.mtc.ca.gov/transit-cost-effectiveness
  val faresByMode: Map[BeamMode, Double] = Map(
    BUS -> 0.99,
    SUBWAY -> 3.43,
    FERRY -> 6.87,
    RAIL -> 4.52
  )

}
