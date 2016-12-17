package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.states.State;

public interface TransitionFactory {
	Transition create(Class transitionClass, State fromState, State toState, Boolean isContingent);
	Transition create(Class transitionClass, State fromState, State toState, Boolean isContingent, GraphVizGraph graph,
			GraphVizScope scope);
}
