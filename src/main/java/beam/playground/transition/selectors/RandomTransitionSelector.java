package beam.playground.transition.selectors;

import java.util.LinkedList;

import beam.EVGlobalData;
import beam.playground.transitions.Transition;

public class RandomTransitionSelector implements TransitionSelector {
	static RandomTransitionSelector instance;

	public static RandomTransitionSelector getInstance(){
		if(instance == null)instance = new RandomTransitionSelector();
		return instance;
	}
	@Override
	public Transition selectTransition(LinkedList<Transition> transitions) {
		return transitions.get(EVGlobalData.data.rand.nextInt(transitions.size()));
	}

}
