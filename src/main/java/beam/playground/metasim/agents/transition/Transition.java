package beam.playground.metasim.agents.transition;

import java.util.HashSet;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;
import org.matsim.core.controler.MatsimServices;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public interface Transition {
	public State getFromState();
	public State getToState();
	public Boolean isContingent();
	public Boolean isAvailableTo(BeamAgent agent);
	public void performTransition(BeamAgent agent);
	public void initialize(State fromState, State toState, Boolean isContingent, GraphVizGraph graph, GraphVizScope scope, BeamServices beamServices, MatsimServices matsimServices);
}
