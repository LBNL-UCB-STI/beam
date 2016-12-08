package beam.playground;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;

import beam.playground.actions.Action;
import beam.playground.actions.BaseAction;
import beam.playground.agents.BeamAgent;
import beam.playground.agents.PersonAgent;
import beam.playground.exceptions.IllegalTransitionException;
import beam.playground.states.BaseState;
import beam.playground.transitions.TransitionFromInActivityToChoosingMode;
import beam.playground.transitions.TransitionFromWalkingToInActivity;
import beam.playground.transitions.TransitionFromChoosingModeToWalking;

public class PlaygroundFun {

	public static void testBeamFSM(){
		GraphVizGraph graph = new GraphVizGraph();
		BaseState scope = new BaseState("Start");
		BaseState inActivity = new BaseState("InActivity",graph,scope);
		BaseState choosingMode = new BaseState("ChoosingMode",graph,scope);
		BaseState walking = new BaseState("Walking",graph,scope);

		inActivity.addTransition(new TransitionFromInActivityToChoosingMode(inActivity, choosingMode, false, graph, scope));
		inActivity.addAction(new BaseAction("EndActivity"));
		choosingMode.addTransition(new TransitionFromChoosingModeToWalking(choosingMode, walking, true, graph, scope));
		choosingMode.addAction(new BaseAction("ChooseMode"));
		walking.addTransition(new TransitionFromWalkingToInActivity(walking, inActivity, false, graph, scope));
		walking.addAction(new BaseAction("Arrive"));
		
		try {
			graph.writeTo(new File("/Users/critter/Downloads/test-graph.dot"));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		PersonAgent agent = new PersonAgent(Id.create(1, BeamAgent.class),inActivity,new Coord(0.0,0.0));
		agent.setState(inActivity); 
		
		for (int i = 0; i < 5; i++) {
			Iterator<Action> iter = agent.getState().getAllActions().iterator();
			try {
				iter.next().perform(agent);
			} catch (IllegalTransitionException e) {
				e.printStackTrace();
			}
		}
	}
}
