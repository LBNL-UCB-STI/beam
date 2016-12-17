package beam.playground.metasim.agents.actions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

import org.matsim.core.controler.MatsimServices;

import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.events.TransitionEvent;
import beam.playground.metasim.exceptions.IllegalTransitionException;
import beam.playground.metasim.services.BeamServices;

public class BaseAction implements Action {
	private BeamServices beamServices;
	//TODO do we need this? get rid of it?
	private MatsimServices matsimServices;
	String name;

	public BaseAction(String name, BeamServices beamServices, MatsimServices matsimServices) {
		super();
		this.name = name;
		this.beamServices = beamServices;
		beamServices.getActions().getActionMap().put(name, this);
		this.matsimServices = matsimServices;
	}

	@Override
	public void initiateAction(BeamAgent agent) throws IllegalTransitionException {
		LinkedList<Transition> availableTransitions = getAvailableTransitions(agent);
		Transition selectedTransition = agent.getTransitionSelector(this).selectTransition(availableTransitions);
		if(!availableTransitions.contains(selectedTransition)){
			throw new IllegalTransitionException("Transition selector " + agent.getTransitionSelector(this) + " selected the transition " + selectedTransition + " which is not available to agent " + agent);
		}
		matsimServices.getEvents().processEvent(new ActionEvent(beamServices.getScheduler().getNow(),agent,this));
		agent.setState(selectedTransition.getToState());
		selectedTransition.performTransition(agent);
		matsimServices.getEvents().processEvent(new TransitionEvent(beamServices.getScheduler().getNow(),agent,selectedTransition));
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
