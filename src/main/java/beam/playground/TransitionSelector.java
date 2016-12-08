package beam.playground;

import java.util.Collection;
import java.util.LinkedList;

public interface TransitionSelector {

	public Transition selectTransition(LinkedList<Transition> transitions);
}
