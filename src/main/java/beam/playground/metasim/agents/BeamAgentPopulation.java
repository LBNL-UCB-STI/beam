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
		
		/* 
		 * PersonAgents
		 * 
		 * We derive person agents from the MATSim population.
		 */
		Scenario scenario = event.getServices().getScenario();
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			BeamAgent newPerson = personAgentFactory.create(person,initialLocation);
			beamAgents.add(newPerson);
			beamServices.getScheduler().addCallBackMethod(0.0, newPerson, "Begin", newPerson.getGraph().getInitialState().getAllTranstions().iterator().next());
		}
	}

	public Collection<BeamAgent> getAgents() {
		return beamAgents;
	}
}
