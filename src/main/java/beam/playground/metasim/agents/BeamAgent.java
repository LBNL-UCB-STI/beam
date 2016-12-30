package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;

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

		public Default(Id<BeamAgent> id, FiniteStateMachineGraph graph, TransitionSelector transitionSelector) {
			super();
			this.id = id;
			this.getClass();
			this.graph = graph;
			this.state = graph.getInitialState();
			this.transitionSelector = transitionSelector;
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
