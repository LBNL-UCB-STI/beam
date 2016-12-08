package beam.playground;

import java.util.Collection;

public interface State {
	public String getName();
	public void addTransition(Transition transition);
	public Collection<Transition> getAllTranstions();
	public Collection<Transition> getContingentTranstions();
	public Collection<Transition> getNonContingentTranstions();
	public void addAction(Action action);
	public Collection<Action> getAllActions();
}
