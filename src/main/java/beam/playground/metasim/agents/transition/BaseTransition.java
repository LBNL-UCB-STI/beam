package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;
import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public abstract class BaseTransition implements Transition, GraphVizScope {
	@Inject protected BeamServices beamServices;
	@Inject protected MatsimServices matsimServices;
	protected State fromState, toState;
	protected Boolean isContingent;
	
	//@Inject
	public BaseTransition(State fromState, State toState, Boolean isContingent) {
		super();
		this.fromState = fromState;
		this.toState = toState;
		this.isContingent = isContingent;
	}

	public BaseTransition(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		this(fromState,toState,isContingent);
		graph.edge(scope, fromState, toState);
//		graph.edge(scope, fromState, toState).label("From"+fromState.getName()+"To"+toState.getName());
	}
	

	@Override
	public State getFromState() {
		return fromState;
	}

	@Override
	public State getToState() {
		return toState;
	}

	@Override
	public Boolean isContingent() {
		return isContingent;
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

}
