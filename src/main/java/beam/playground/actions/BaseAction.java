package beam.playground.actions;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

import beam.EVGlobalData;
import beam.playground.PlaygroundFun;
import beam.playground.agents.BeamAgent;
import beam.playground.events.ActionEvent;
import beam.playground.events.TransitionEvent;
import beam.playground.exceptions.IllegalTransitionException;
import beam.playground.transitions.Transition;

public class BaseAction implements Action {
	String name;

	public BaseAction(String name) {
		super();
		this.name = name;
		//TODO need a real registry of actions
		PlaygroundFun.actions.put(name, this);
	}

	@Override
	public void initiateAction(BeamAgent agent) throws IllegalTransitionException {
		LinkedList<Transition> availableTransitions = getAvailableTransitions(agent);
		Transition selectedTransition = agent.getTransitionSelector(this).selectTransition(availableTransitions);
		if(!availableTransitions.contains(selectedTransition)){
			throw new IllegalTransitionException("Transition selector " + agent.getTransitionSelector(this) + " selected the transition " + selectedTransition + " which is not available to agent " + agent);
		}
		EVGlobalData.data.eventLogger.processEvent(new ActionEvent(EVGlobalData.data.now,agent,this));
		agent.setState(selectedTransition.getToState());
		selectedTransition.performTransition(agent);
		EVGlobalData.data.eventLogger.processEvent(new TransitionEvent(EVGlobalData.data.now,agent,selectedTransition));
	}

	private LinkedList<Transition> getAvailableTransitions(BeamAgent agent) {
		LinkedList<Transition> resultingTransitions = new LinkedList<Transition>(agent.getState().getNonContingentTranstions());
		Collection<Transition> candidateTransitions = new LinkedList<Transition>(agent.getState().getContingentTranstions());
		for(Transition transition : candidateTransitions){
			if(transition.isAvailableTo(agent))resultingTransitions.add(transition);
		}
		return resultingTransitions;
	}

	@Override
	public String getName() {
		if(name==null)name=this.getClass().getSimpleName();
		return name;
	}

}
