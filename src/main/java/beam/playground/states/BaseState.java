package beam.playground.states;

import java.util.Collection;
import java.util.LinkedList;

import beam.playground.actions.Action;
import beam.playground.transitions.Transition;

public class BaseState implements State {
	private String name;
	private LinkedList<Transition> contingentTransitionsFromThisState = new LinkedList<>(), nonContingentTransitionsFromThisState = new LinkedList<>();
	private LinkedList<Action> actions = new LinkedList<>();

	public BaseState(String name) {
		super();
		this.name = name;
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
