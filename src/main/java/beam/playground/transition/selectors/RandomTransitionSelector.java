package beam.playground;

import java.util.LinkedList;

import beam.EVGlobalData;

public class RandomTransitionSelector implements TransitionSelector {
	static RandomTransitionSelector instance;

	static RandomTransitionSelector getInstance(){
		if(instance == null)instance = new RandomTransitionSelector();
		return instance;
	}
	@Override
	public Transition selectTransition(LinkedList<Transition> transitions) {
		return transitions.get(EVGlobalData.data.rand.nextInt(transitions.size()));
	}

}
