package beam.playground.metasim;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.hsqldb.lib.HashMap;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.playground.metasim.actions.Action;
import beam.playground.metasim.actions.BaseAction;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.exceptions.IllegalTransitionException;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.states.BaseState;
import beam.playground.metasim.transitions.BaseTransition;
import beam.playground.metasim.transitions.TransitionFromChoosingModeToInActivity;
import beam.playground.metasim.transitions.TransitionFromChoosingModeToWalking;
import beam.playground.metasim.transitions.TransitionFromDrivingToDriving;
import beam.playground.metasim.transitions.TransitionFromDrivingToParking;
import beam.playground.metasim.transitions.TransitionFromInActivityToChoosingMode;
import beam.playground.metasim.transitions.TransitionFromInActivityToWalking;
import beam.playground.metasim.transitions.TransitionFromParkingToWalking;
import beam.playground.metasim.transitions.TransitionFromStartToInActivity;
import beam.playground.metasim.transitions.TransitionFromWalkingToDriving;
import beam.playground.metasim.transitions.TransitionFromWalkingToInActivity;
import beam.playground.metasim.transitions.TransitionFromWalkingToWalking;

public class PlaygroundFun {
	public static HashMap actions = new HashMap();
	public static Scheduler scheduler = new Scheduler();

	public static void testBeamFSM(){

		GraphVizGraph graph = new GraphVizGraph();
		BaseState start = new BaseState("Start");
		BaseState inActivity = new BaseState("InActivity",graph,start);
		BaseState choosingMode = new BaseState("ChoosingMode",graph,start);
		BaseState walking = new BaseState("Walking",graph,start);
		BaseState driving = new BaseState("Driving",graph,start);
		BaseState parking = new BaseState("Parking",graph,start);

		BaseTransition startTransition = new TransitionFromStartToInActivity(start, inActivity, false);
		start.addTransition(startTransition);

		inActivity.addAction(new BaseAction("PlanNextLeg"));
		inActivity.addAction(new BaseAction("EndActivity"));
		inActivity.addTransition(new TransitionFromInActivityToChoosingMode(inActivity, choosingMode, false, graph, start));
		inActivity.addTransition(new TransitionFromInActivityToWalking(inActivity, walking, false, graph, start));

		choosingMode.addAction(new BaseAction("ChooseMode"));
		choosingMode.addTransition(new TransitionFromChoosingModeToWalking(choosingMode, walking, false, graph, start));
		choosingMode.addTransition(new TransitionFromChoosingModeToInActivity(choosingMode, inActivity, false, graph, start));

		walking.addAction(new BaseAction("Arrive"));
		walking.addTransition(new TransitionFromWalkingToInActivity(walking, inActivity, false, graph, start));
		walking.addTransition(new TransitionFromWalkingToWalking(walking, walking, false, graph, start));
		walking.addTransition(new TransitionFromWalkingToDriving(walking, driving, false, graph, start));

		driving.addAction(new BaseAction("ExitEnterLink"));
		driving.addAction(new BaseAction("Arrive"));
		driving.addTransition(new TransitionFromDrivingToDriving(driving, driving, false, graph, start));
		driving.addTransition(new TransitionFromDrivingToParking(driving, parking, false, graph, start));

		parking.addAction(new BaseAction("ExitVehicle"));
		parking.addTransition(new TransitionFromParkingToWalking(parking, walking, false, graph, start));
		
		try {
			graph.writeTo(new File("/Users/critter/Downloads/test-graph.dot"));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		PersonAgent agent = new PersonAgent(Id.create(1, Person.class),inActivity,new Coord(0.0,0.0));
		agent.setState(inActivity); 
		
		scheduler.addCallBackMethod(0.0, agent, "EndActivity", startTransition);
		
		for (int i = 0; i < 1000; i++) {
			EVGlobalData.data.now = i;
			PlaygroundFun.scheduler.doSimStep(i);
		}
	}
}
