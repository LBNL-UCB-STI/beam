package beam.agentsim.agents.planning

import beam.agentsim.agents.planning.BeamPlan.atHome
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.TourModes.BeamTourMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity

/**
  * BEAM
  */
object Strategy {

  trait Strategy {

    /**
      * When a tour strategy is set this method of that strategy is called. It allows to set strategies for the trips
      * that this tour consists of
      * @param tour the tour that this strategy is set for
      * @param beamPlan the whole beam plan
      * @return trips with strategies that needs to be set
      */
    def tripStrategies(tour: Tour, beamPlan: BeamPlan): Seq[(Trip, Strategy)] = Seq.empty
  }

  case class TourModeChoiceStrategy(tourMode: Option[BeamTourMode] = None, tourVehicle: Option[Id[BeamVehicle]] = None)
      extends Strategy {
    def this() = this(None)
  }

  case class TripModeChoiceStrategy(mode: Option[BeamMode] = None) extends Strategy {
    def this() = this(None)

    override def tripStrategies(tour: Tour, beamPlan: BeamPlan): Seq[(Trip, Strategy)] = {
      val tourStrategy = this

      mode match {
        case Some(CAR | BIKE) =>
          tour.trips.zip(Seq.fill(tour.trips.size)(tourStrategy))
        case Some(DRIVE_TRANSIT | BIKE_TRANSIT) =>
          //replace both ends with DRIVE_TRANSIT or BIKE_TRANSIT
          val firstTrip = tour.trips.head
          val lastTrip = tour.trips.last
          Seq(firstTrip -> tourStrategy, lastTrip -> tourStrategy)
        case Some(HOV2_TELEPORTATION | HOV3_TELEPORTATION) =>
          //we need to replace all CAR_HOV modes to this tour mode
          //because these CAR_HOV modes shouldn't be used in this type of tour
          tour.trips
            .withFilter { trip =>
              beamPlan.getStrategy[TripModeChoiceStrategy](trip).mode match {
                case Some(CAR_HOV2 | CAR_HOV3) => true
                case _                         => false
              }
            }
            .map(_ -> tourStrategy)
        case _ => super.tripStrategies(tour, beamPlan)
      }
    }
  }

}
