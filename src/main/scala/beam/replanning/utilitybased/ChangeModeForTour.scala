package beam.replanning.utilitybased

import beam.agentsim.agents.household.Memberships.HouseholdMemberships
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.replanning.utilitybased.ChangeModeForTour.TourModeIdentifier
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Plan
import org.matsim.core.population.algorithms.PlanAlgorithm

class ChangeModeForTour(val tourModeIdentifier: TourModeIdentifier, householdMemberships: HouseholdMemberships, beamServices: BeamServices) extends PlanAlgorithm {

//
//  def findAvailableModesPerPlan(beamPlan:BeamPlan):Seq[BeamMode]={
//    val person = beamPlan.getPerson
//    val household = householdMemberships.memberships(person.getId)
//    val cars = JavaConverters.asScalaBuffer(household.getVehicleIds).map(vehId=>beamServices.vehicles(vehId)).filter(beamVehicle=>beamVehicle.beamVehicleType.equals(Car))
//
//
//  }

//  def rankModeChoice(tour: Tour) =

  //
  //  def propagatesVehicleConstraints(tour:Tour, beamMode: BeamMode):Boolean={
  //    tour.trips
  //  }
  //
  //  def addPlanStrategyForTrip(trip:Trip):{
  //
  //  }

  override def run(plan: Plan): Unit = {
    val beamPlan = BeamPlan(plan)
    for {tour <- beamPlan.tours if tourModeIdentifier.tour.equals(tour)
         trip <- tour.trips
         leg <- trip.leg
    } yield {
      leg.setMode(tourModeIdentifier.beamMode.matsimMode)
    }
  }
}

object ChangeModeForTour {

  case class TourModeIdentifier(tour: Tour, beamMode: BeamMode, priority: Int) extends Ordered[TourModeIdentifier] {
    val isChainBasedTour: Boolean = beamMode.isChainBasedMode

    override def compare(that: TourModeIdentifier): Int =
      if (this.priority > that.priority) 1
      else if (this.priority == that.priority) 0
      else -1
  }

}
