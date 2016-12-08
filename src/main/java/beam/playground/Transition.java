package beam.playground;

import java.util.HashSet;

public interface Transition {
	public State getFromState();
	public State getToState();
	public Boolean isContingent();
	public Boolean isAvailableTo(BeamAgent agent);
}
