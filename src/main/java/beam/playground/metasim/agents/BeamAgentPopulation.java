package beam.playground.metasim.agents;

import java.util.Collection;
import java.util.LinkedHashSet;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;

import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.TransitionFactory;
import beam.playground.metasim.agents.transition.TransitionFromStartToInActivity;
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
		Transition.Default nullTransition = (Transition.Default) transitionFactory.create(TransitionFromStartToInActivity.class, null, null, false);
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			BeamAgent newPerson = personAgentFactory.create(person.getId(),initialLocation);
			beamAgents.add(newPerson);
			beamServices.getScheduler().addCallBackMethod(0.0, newPerson, "Begin", newPerson.getGraph().getInitialState().getAllTranstions().iterator().next());
		}
	/*	
		GraphVizGraph graph = new GraphVizGraph();
		Default inActivity = new Default("InActivity",graph,start);
		Default choosingMode = new Default("ChoosingMode",graph,start);
		Default walking = new Default("Walking",graph,start);
		Default driving = new Default("Driving",graph,start);
		Default parking = new Default("Parking",graph,start);

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
		*/

	}

	public Collection<BeamAgent> getAgents() {
		return beamAgents;
	}
}
