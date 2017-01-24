package beam.playground.metasim.agents;


import org.matsim.api.core.v01.Id;

import akka.actor.UntypedActor;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.choice.models.ChoiceModel;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public interface BeamAgent {
	public Id<BeamAgent> getId();
	public State getState();
	public FiniteStateMachineGraph getGraph();
	public ChoiceModel getChoiceModel(Action action);
	public void performTransition(Transition selectedTransition);
	public void setState(State toState);
	
	public class Default extends UntypedActor implements BeamAgent {
		protected Id<BeamAgent> id;
		protected State state;
		protected FiniteStateMachineGraph graph;
		protected BeamServices beamServices;

		public Default(Id<BeamAgent> id, BeamServices beamServices) {
			super();
			this.id = id;
			this.getClass();
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
		public ChoiceModel getChoiceModel(Action action) {
			return null;
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

		@Override
		public void onReceive(Object arg0) throws Throwable {
		}


	}

}
