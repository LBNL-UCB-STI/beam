package beam.playground.metasim.agents;


import org.matsim.api.core.v01.Id;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.services.BeamServices;

public interface BeamAgent {
	public Id<BeamAgent> getId();
	public State getState();
	public FiniteStateMachineGraph getGraph();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
	public void setState(State toState);
	
	public class Default implements BeamAgent {
		protected Id<BeamAgent> id;
		protected State state;
		protected TransitionSelector transitionSelector;
		protected FiniteStateMachineGraph graph;
		protected BeamServices beamServices;

		public Default(Id<BeamAgent> id, TransitionSelector transitionSelector, BeamServices beamServices) {
			super();
			this.id = id;
			this.getClass();
			this.transitionSelector = transitionSelector;
			this.beamServices = beamServices;
			this.graph = beamServices.getFiniteStateMachineGraphFor(this.getClass());
			this.state = this.graph.getInitialState();
		}

		@Override
		public Id<BeamAgent> getId() {
			return id;
		}

		@Override
		public State getState() {
			return state;
		}

		@Override
		public TransitionSelector getTransitionSelector(Action action) {
			return transitionSelector;
		}

		@Override
		public void performTransition(Transition selectedTransition) {
			state = selectedTransition.getToState();
		}

		public void setState(State state) {
			this.state = state;
		}

		@Override
		public FiniteStateMachineGraph getGraph() {
			return graph;
		}


	}

}
