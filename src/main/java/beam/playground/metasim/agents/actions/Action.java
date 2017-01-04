package beam.playground.metasim.agents.actions;

import java.util.Collection;
import java.util.LinkedList;

import javax.annotation.Nullable;

import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.behavior.ChoiceModel;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.events.TransitionEvent;
import beam.playground.metasim.exceptions.IllegalTransitionException;
import beam.playground.metasim.services.BeamServices;

public interface Action {

    public void performOn(BeamAgent agent) throws IllegalTransitionException;
	public String getName(); 

    public class Default implements Action {
    	private BeamServices beamServices;
    	private MatsimServices matsimServices;
    	private String name;
    	private LinkedList<Transition> restrictedTransitions; // Null for no restriction
    	private Class<?> defaultChoiceModel;

		@Inject
    	public Default(@Assisted String name, @Nullable @Assisted LinkedList<Transition> restrictedTransitions, BeamServices beamServices, MatsimServices matsimServices) {
    		super();
    		this.name = name;
    		this.beamServices = beamServices;
    		this.matsimServices = matsimServices;
    		this.restrictedTransitions = restrictedTransitions;
    	}

    	@Override
    	public void performOn(BeamAgent agent) throws IllegalTransitionException {
    		LinkedList<Transition> availableTransitions = getAvailableTransitions(agent);
    		Transition selectedTransition = agent.getChoiceModel(this).selectTransition(availableTransitions);
    		if(!availableTransitions.contains(selectedTransition)){
    			throw new IllegalTransitionException("Transition selector " + agent.getChoiceModel(this) + " selected the transition " + selectedTransition + " which is not available to agent " + agent);
    		}
    		matsimServices.getEvents().processEvent(new ActionEvent(beamServices.getScheduler().getNow(),agent,this));
    		agent.setState(selectedTransition.getToState());
    		selectedTransition.performTransition(agent);
    		matsimServices.getEvents().processEvent(new TransitionEvent(beamServices.getScheduler().getNow(),agent,selectedTransition));
    	}

    	private LinkedList<Transition> getAvailableTransitions(BeamAgent agent) {
    		if(restrictedTransitions == null){
    			LinkedList<Transition> resultingTransitions = new LinkedList<Transition>(agent.getState().getNonContingentTranstions());
				Collection<Transition> candidateTransitions = new LinkedList<Transition>(agent.getState().getContingentTranstions());
				for(Transition transition : candidateTransitions){
					if(transition.isAvailableTo(agent))resultingTransitions.add(transition);
				}
				return resultingTransitions;
    		}else{
    			//TODO decide whether we need to check isAvailableTo on these
    			return restrictedTransitions;
    		}
    	}

    	@Override
    	public String getName() {
    		if(name==null)name=this.getClass().getSimpleName();
    		return name;
    	}

		public String toString(){
			return "Action:"+name;
		}

    	public Class<?> getDefaultChoiceModel() {
			return defaultChoiceModel;
		}
		public void setDefaultChoiceModel(Class<?> defaultChoiceModel) {
			this.defaultChoiceModel = defaultChoiceModel;
		}
    }

}
