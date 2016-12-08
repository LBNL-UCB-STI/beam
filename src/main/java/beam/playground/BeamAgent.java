package beam.playground;

public interface BeamAgent {
	public State getState();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
}
