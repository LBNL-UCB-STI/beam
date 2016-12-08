package beam.playground.actions;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

import beam.EVGlobalData;
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
	}

	@Override
	public void perform(BeamAgent agent) throws IllegalTransitionException {
		LinkedList<Transition> availableTransitions = getAvailableTransitions(agent);
		Transition selectedTransition = agent.getTransitionSelector(this).selectTransition(availableTransitions);
		if(!availableTransitions.contains(selectedTransition)){
			throw new IllegalTransitionException("Transition selector " + agent.getTransitionSelector(this) + " selected the transition " + selectedTransition + " which is not available to agent " + agent);
		}
		EVGlobalData.data.eventLogger.processEvent(new ActionEvent(EVGlobalData.data.now,agent,this));
		agent.performTransition(selectedTransition);
		EVGlobalData.data.eventLogger.processEvent(new TransitionEvent(EVGlobalData.data.now,agent,selectedTransition));
		//Schedule new action here....
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
		return name;
	}

}
