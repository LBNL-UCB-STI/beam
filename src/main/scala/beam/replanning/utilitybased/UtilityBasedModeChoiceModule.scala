package beam.replanning.utilitybased


import beam.agentsim.agents.choice.mode.{ModeChoiceLCCM, ModeChoiceMultinomialLogit}
import beam.sim.BeamServices
import com.google.inject.Inject
import org.matsim.core.config.groups.GlobalConfigGroup
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.replanning.modules.AbstractMultithreadedModule

class UtilityBasedModeChoiceModule @Inject()(config: GlobalConfigGroup, beamServices: BeamServices) extends AbstractMultithreadedModule(config) {


  override def getPlanAlgoInstance: PlanAlgorithm = {
    val modeChoiceCalculatorFactory = beamServices.modeChoiceCalculatorFactory.apply()
    modeChoiceCalculatorFactory match {
      case ModeChoiceMultinomialLogit(_,_)=>
        new MultinomialLogitScoringAlgorithm(modeChoiceCalculatorFactory.asInstanceOf[ModeChoiceMultinomialLogit])
    }

  }
}
