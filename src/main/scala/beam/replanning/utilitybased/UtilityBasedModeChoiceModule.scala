package beam.replanning.utilitybased


import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.planning.Strategy.ModeChoiceStrategy
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import com.google.inject.Inject
import org.matsim.api.core.v01.population.Plan
import org.matsim.core.config.groups.GlobalConfigGroup
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.replanning.modules.AbstractMultithreadedModule

import scala.collection.JavaConverters

class UtilityBasedModeChoiceModule @Inject()(config: GlobalConfigGroup, beamServices: BeamServices) extends AbstractMultithreadedModule(config) {



  override def getPlanAlgoInstance: PlanAlgorithm = {
    val modeChoiceClass = beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
    val planAlgo: PlanAlgorithm = modeChoiceClass match {
      case "ModeChoiceMultinomialLogit" => (plan: Plan) => {
        // Associate the main mode of each plan with the plan's score
        val personBeamPlansWithLegs = JavaConverters.asScalaBuffer(plan.getPerson.getPlans).map(BeamPlan(_)).filter(plan => plan.legs.nonEmpty)
        if (personBeamPlansWithLegs.nonEmpty) {
          val maxPlanMode = personBeamPlansWithLegs.map(plan => {
            val planModes = plan.legs.map(leg => Option(leg.getMode).getOrElse(""))
            val mostFrequentPlanMode = planModes.groupBy(identity).maxBy(_._2.size)._1
            mostFrequentPlanMode -> plan.getScore
          }).maxBy(_._2)._1

          val beamPlan = BeamPlan(plan)

          beamPlan.tours.foreach(tour => {
            // Work on a single tour at a time
            tour.trips.zipWithIndex foreach { case (trip, idx) =>
              trip.leg.foreach(
                leg => {
                  val legMode = BeamMode.withValue(maxPlanMode)
                  beamPlan.putStrategy(leg, ModeChoiceStrategy(legMode))
                }
              )
            }
          })
        }
      }
      case _ => (plan: Plan) => {
        // Do Nothing
      }
    }
    planAlgo
  }
}

object UtilityBasedModeChoiceModule {

}
