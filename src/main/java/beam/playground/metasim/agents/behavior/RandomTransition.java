package beam.playground.metasim.agents.transition.selectors;

import java.util.LinkedList;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

@Singleton
public class RandomTransitionSelector implements TransitionSelector {
	private BeamServices beamServices;

	@Inject
	public RandomTransitionSelector(BeamServices beamServices){
		super();
		this.beamServices = beamServices;
	}
	@Override
	public Transition selectTransition(LinkedList<Transition> transitions) {
		return transitions.size() == 0 ? null : transitions.get(beamServices.getRandom().nextInt(transitions.size()));
	}

}
