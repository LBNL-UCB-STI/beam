package beam.playground.metasim.agents.plans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.matsim.api.core.v01.population.Plan;

import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.events.TransitionEvent;
import beam.playground.metasim.services.BeamServices;

public interface BeamPlan {

	public Plan getMatsimPlan();
	public List<TransitionEvent> getTransitionEventHistory();
	public TransitionSelector getTransitionSelectorFor(Action action);

	public class Default implements BeamPlan{
		private Plan plan;
		private ArrayList<TransitionEvent> transitionEventHistory;
		private LinkedHashMap<Action,TransitionSelector> selectorMap = new LinkedHashMap<>();
		private BeamServices beamServices;

		public Default(PersonAgent personAgent, Plan plan, BeamServices beamServices){
			this.plan = plan;
			this.beamServices = beamServices;
		}

		@Override
		public Plan getMatsimPlan() {
			return plan;
		}

		@Override
		public List<TransitionEvent> getTransitionEventHistory() {
			return transitionEventHistory;
		}

		@Override
		public TransitionSelector getTransitionSelectorFor(Action action) {
			return selectorMap.containsKey(action) ? selectorMap.get(action) : beamServices.getActions().getDefaultTranitionSelectorForAction(action);
		}
		
	}
}
