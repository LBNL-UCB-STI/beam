package beam.playground.metasim.agents;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;

import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.actions.BaseAction;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.BaseTransition;
import beam.playground.metasim.agents.transition.TransitionFromChoosingModeToInActivity;
import beam.playground.metasim.agents.transition.TransitionFromChoosingModeToWalking;
import beam.playground.metasim.agents.transition.TransitionFromDrivingToDriving;
import beam.playground.metasim.agents.transition.TransitionFromDrivingToParking;
import beam.playground.metasim.agents.transition.TransitionFromInActivityToChoosingMode;
import beam.playground.metasim.agents.transition.TransitionFromInActivityToWalking;
import beam.playground.metasim.agents.transition.TransitionFromParkingToWalking;
import beam.playground.metasim.agents.transition.TransitionFromStartToInActivity;
import beam.playground.metasim.agents.transition.TransitionFromWalkingToDriving;
import beam.playground.metasim.agents.transition.TransitionFromWalkingToInActivity;
import beam.playground.metasim.agents.transition.TransitionFromWalkingToWalking;
import beam.playground.metasim.services.BeamServices;

public class BeamAgentPopulation implements StartupListener{
	LinkedHashSet<BeamAgent> beamAgents;
	@Inject BeamServices beamServices;
	@Inject ActionFactory actionFactory;

	@Override
	public void notifyStartup(StartupEvent event) {
		beamAgents = new LinkedHashSet<BeamAgent>();
		Scenario scenario = event.getServices().getScenario();
		State startState = new BaseState("start");
		BaseTransition nullTransition = new BaseTransition(null,null,false) {
			@Override
			public void performTransition(BeamAgent agent) {
			}
		};
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			BeamAgent newPerson = new PersonAgent(person.getId(),startState,initialLocation);
			beamAgents.add(newPerson);
			beamServices.getScheduler().addCallBackMethod(0.0, newPerson, "start", nullTransition);
		}
		
		GraphVizGraph graph = new GraphVizGraph();
		BaseState start = new BaseState("Start");
		BaseState inActivity = new BaseState("InActivity",graph,start);
		BaseState choosingMode = new BaseState("ChoosingMode",graph,start);
		BaseState walking = new BaseState("Walking",graph,start);
		BaseState driving = new BaseState("Driving",graph,start);
		BaseState parking = new BaseState("Parking",graph,start);

		BaseTransition startTransition = new TransitionFromStartToInActivity(start, inActivity, false);
		start.addAction(actionFactory.create("start"));
		start.addTransition(startTransition);

		inActivity.addAction(actionFactory.create("PlanNextLeg"));
		inActivity.addAction(actionFactory.create("EndActivity"));
		inActivity.addTransition(new TransitionFromInActivityToChoosingMode(inActivity, choosingMode, false, graph, start));
		inActivity.addTransition(new TransitionFromInActivityToWalking(inActivity, walking, false, graph, start));

		choosingMode.addAction(actionFactory.create("ChooseMode"));
		choosingMode.addTransition(new TransitionFromChoosingModeToWalking(choosingMode, walking, false, graph, start));
		choosingMode.addTransition(new TransitionFromChoosingModeToInActivity(choosingMode, inActivity, false, graph, start));

		walking.addAction(actionFactory.create("Arrive"));
		walking.addTransition(new TransitionFromWalkingToInActivity(walking, inActivity, false, graph, start));
		walking.addTransition(new TransitionFromWalkingToWalking(walking, walking, false, graph, start));
		walking.addTransition(new TransitionFromWalkingToDriving(walking, driving, false, graph, start));

		driving.addAction(actionFactory.create("ExitEnterLink"));
		driving.addAction(actionFactory.create("Arrive"));
		driving.addTransition(new TransitionFromDrivingToDriving(driving, driving, false, graph, start));
		driving.addTransition(new TransitionFromDrivingToParking(driving, parking, false, graph, start));

		parking.addAction(actionFactory.create("ExitVehicle"));
		parking.addTransition(new TransitionFromParkingToWalking(parking, walking, false, graph, start));
		
		try {
			graph.writeTo(new File("/Users/critter/Downloads/test-graph.dot"));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

	}

	public Collection<BeamAgent> getAgents() {
		return beamAgents;
	}
}
