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
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.TransitionFactory;
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
	@Inject PersonAgentFactory personAgentFactory;
	@Inject TransitionFactory transitionFactory;

	@Override
	public void notifyStartup(StartupEvent event) {
		beamAgents = new LinkedHashSet<BeamAgent>();
		Scenario scenario = event.getServices().getScenario();
		BaseState start = new BaseState("Start");
		BaseTransition nullTransition = (BaseTransition) transitionFactory.create(TransitionFromStartToInActivity.class, null, null, false);
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			BeamAgent newPerson = personAgentFactory.create(person.getId(),start,initialLocation);
			beamAgents.add(newPerson);
			beamServices.getScheduler().addCallBackMethod(0.0, newPerson, "Start", nullTransition);
		}
		
		GraphVizGraph graph = new GraphVizGraph();
		BaseState inActivity = new BaseState("InActivity",graph,start);
		BaseState choosingMode = new BaseState("ChoosingMode",graph,start);
		BaseState walking = new BaseState("Walking",graph,start);
		BaseState driving = new BaseState("Driving",graph,start);
		BaseState parking = new BaseState("Parking",graph,start);

		Transition startTransition = transitionFactory.create(TransitionFromStartToInActivity.class, start, inActivity, false);
		start.addAction(actionFactory.create("Start"));
		start.addTransition(startTransition);

		inActivity.addAction(actionFactory.create("PlanNextLeg"));
		inActivity.addAction(actionFactory.create("EndActivity"));
		inActivity.addTransition(transitionFactory.create(TransitionFromInActivityToChoosingMode.class, inActivity, choosingMode, false));
		inActivity.addTransition(transitionFactory.create(TransitionFromInActivityToWalking.class, inActivity, walking, false));

		choosingMode.addAction(actionFactory.create("ChooseMode"));
		choosingMode.addTransition(transitionFactory.create(TransitionFromChoosingModeToWalking.class,choosingMode ,walking , false));
		choosingMode.addTransition(transitionFactory.create(TransitionFromChoosingModeToInActivity.class,choosingMode , inActivity, false));

		walking.addAction(actionFactory.create("Arrive"));
		walking.addTransition(transitionFactory.create(TransitionFromWalkingToInActivity.class, walking, inActivity, false));
		walking.addTransition(transitionFactory.create(TransitionFromWalkingToWalking.class, walking, walking, false));
		walking.addTransition(transitionFactory.create(TransitionFromWalkingToDriving.class, walking, driving, false));

		driving.addAction(actionFactory.create("ExitEnterLink"));
		driving.addAction(actionFactory.create("Arrive"));
		driving.addTransition(transitionFactory.create(TransitionFromDrivingToDriving.class, driving, driving, false));
		driving.addTransition(transitionFactory.create(TransitionFromDrivingToParking.class, driving, parking, false));

		parking.addAction(actionFactory.create("ExitVehicle"));
		parking.addTransition(transitionFactory.create(TransitionFromParkingToWalking.class, parking, walking, false));
		
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
