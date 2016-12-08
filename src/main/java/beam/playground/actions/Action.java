package beam.playground;

import java.util.Collection;

public interface Action {

    public void perform(BeamAgent agent) throws IllegalTransitionException;

}
