package beam.playground.metasim.agents.states;

import java.util.Collection;
import java.util.LinkedList;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;
import org.anarres.graphviz.builder.GraphVizScope.Impl;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.Transition;

public class BaseState implements State,GraphVizScope {
	private String name;
	private LinkedList<Transition> contingentTransitionsFromThisState = new LinkedList<>(), nonContingentTransitionsFromThisState = new LinkedList<>();
	private LinkedList<Action> actions = new LinkedList<>();

	public BaseState(String name) {
		super();
		this.name = name;
	}

	public BaseState(String name, GraphVizGraph graph, BaseState scope) {
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

}
