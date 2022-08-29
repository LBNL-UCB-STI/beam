package beam.agentsim.agents.planning

import beam.agentsim.agents.planning.BeamPlan.atHome
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
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

  case class ModeChoiceStrategy(mode: Option[BeamMode]) extends Strategy {

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
              beamPlan.getStrategy[ModeChoiceStrategy](trip).flatMap(_.mode) match {
                case Some(CAR_HOV2 | CAR_HOV3) => true
                case _                         => false
              }
            }
            .map(_ -> tourStrategy)
        case _ => super.tripStrategies(tour, beamPlan)
      }
    }

    def tourStrategy(beamPlan: BeamPlan, curAct: Activity, nextAct: Activity): ModeChoiceStrategy = {
      val currentTourModeOpt = beamPlan.getTourStrategy[ModeChoiceStrategy](nextAct).flatMap(_.mode)
      val newTourMode = currentTourModeOpt match {
        case Some(_) if mode.get.isHovTeleportation => mode
        case Some(DRIVE_TRANSIT | BIKE_TRANSIT)     => currentTourModeOpt
        case _ if atHome(curAct)                    => mode
        case Some(_)                                => currentTourModeOpt
        case None                                   => mode
      }
      ModeChoiceStrategy(newTourMode)
    }

  }

}
