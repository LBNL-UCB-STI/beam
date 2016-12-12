package beam.playground.actions;

import java.util.Collection;

import beam.playground.agents.BeamAgent;
import beam.playground.exceptions.IllegalTransitionException;

public interface Action {

    public void initiateAction(BeamAgent agent) throws IllegalTransitionException;
    public String getName(); 

}
