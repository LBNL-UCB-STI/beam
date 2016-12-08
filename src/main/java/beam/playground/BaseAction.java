package beam.playground;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

public class BaseAction implements Action {

	@Override
	public void perform(BeamAgent agent) throws IllegalTransitionException {
		LinkedList<Transition> availableTransitions = getAvailableTransitions(agent);
		Transition selectedTransition = agent.getTransitionSelector(this).selectTransition(availableTransitions);
		if(!availableTransitions.contains(selectedTransition)){
			throw new IllegalTransitionException("Transition selector " + agent.getTransitionSelector(this) + " selected the transition " + selectedTransition + " which is not available to agent " + agent);
		}
		agent.performTransition(selectedTransition);
	}

	private LinkedList<Transition> getAvailableTransitions(BeamAgent agent) {
		LinkedList<Transition> resultingTransitions = new LinkedList<Transition>(agent.getState().getNonContingentTranstions());
		Collection<Transition> candidateTransitions = new LinkedList<Transition>(agent.getState().getContingentTranstions());
		for(Transition transition : candidateTransitions){
			if(transition.isAvailableTo(agent))resultingTransitions.add(transition);
		}
		return resultingTransitions;
	}

}
