package beam.playground.metasim.actions;

import java.util.Collection;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.exceptions.IllegalTransitionException;

public interface Action {

    public void initiateAction(BeamAgent agent) throws IllegalTransitionException;
    public String getName(); 

}
