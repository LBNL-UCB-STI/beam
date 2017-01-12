package beam.playground.metasim.agents.plans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.matsim.api.core.v01.population.Plan;

import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.choice.models.ChoiceModel;
import beam.playground.metasim.events.TransitionEvent;
import beam.playground.metasim.services.BeamServices;

public interface BeamPlan {

	public Plan getMatsimPlan();
	public List<TransitionEvent> getTransitionEventHistory();
	public ChoiceModel getChoiceModelFor(Action action);

	public class Default implements BeamPlan{
		private Plan plan;
		private ArrayList<TransitionEvent> transitionEventHistory;
		private LinkedHashMap<Action,ChoiceModel> selectorMap = new LinkedHashMap<>();
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
		public ChoiceModel getChoiceModelFor(Action action) {
			return selectorMap.containsKey(action) ? selectorMap.get(action) : beamServices.getChoiceModelService().getDefaultChoiceModelForAction(action);
		}
		
	}
}
