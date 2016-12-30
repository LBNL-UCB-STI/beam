package beam.playground.metasim.agents.states;

import java.util.Collection;
import java.util.LinkedList;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.Transition;

public interface State {
	public String getName();
	public void addTransition(Transition transition);
	public Collection<Transition> getAllTranstions();
	public Collection<Transition> getContingentTranstions();
	public Collection<Transition> getNonContingentTranstions();
	public void addAction(Action action);
	public Collection<Action> getAllActions();
	
	public class Default implements State,GraphVizScope {
		private String name;
		private LinkedList<Transition> contingentTransitionsFromThisState = new LinkedList<>(), nonContingentTransitionsFromThisState = new LinkedList<>();
		private LinkedList<Action> actions = new LinkedList<>();

		public Default(String name) {
			super();
			this.name = name;
		}

		public Default(String name, GraphVizGraph graph, Default scope) {
			this(name);
			graph.node(scope, this).label(name);
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void addTransition(Transition transition) {
			if(transition.isContingent()){
				contingentTransitionsFromThisState.add(transition);
			}else{
				nonContingentTransitionsFromThisState.add(transition);
			}
		}

		@Override
		public Collection<Transition> getAllTranstions() {
			LinkedList<Transition> allTransitions = new LinkedList<>();
			allTransitions.addAll(contingentTransitionsFromThisState);
			allTransitions.addAll(nonContingentTransitionsFromThisState);
			return allTransitions;
		}

		@Override
		public Collection<Transition> getContingentTranstions() {
			return contingentTransitionsFromThisState;
		}

		@Override
		public Collection<Transition> getNonContingentTranstions() {
			return nonContingentTransitionsFromThisState;
		}

		@Override
		public void addAction(Action action) {
			actions.add(action);
		}

		@Override
		public Collection<Action> getAllActions() {
			return actions;
		}
		
		public String toString(){
			return "State:"+name;
		}

	}

}
