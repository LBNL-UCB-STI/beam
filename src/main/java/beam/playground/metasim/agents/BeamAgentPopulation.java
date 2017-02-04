package beam.playground.metasim.agents;

import java.util.LinkedHashMap;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;

import beam.metasim.playground.colin.BeamAkkaSystem;
import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.transition.TransitionFactory;
import beam.playground.metasim.services.BeamServices;

public class BeamAgentPopulation implements StartupListener{
	LinkedHashMap<Id<Person>,PersonAgent> personAgents;
	@Inject BeamServices beamServices;
	@Inject ActionFactory actionFactory;
	@Inject PersonAgentFactory personAgentFactory;
	@Inject TransitionFactory transitionFactory;

	@Override
	public void notifyStartup(StartupEvent event) {
		beamServices.setBeamAgentPopulation(this);
		
		BeamAkkaSystem main = new BeamAkkaSystem();
		main.init();
		main.start();
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.exit(0);
		/* 
		 * PersonAgents
		 * 
		 * We derive person agents from the MATSim population.
		 */
		personAgents = new LinkedHashMap<Id<Person>,PersonAgent>();
		Scenario scenario = event.getServices().getScenario();
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			PersonAgent newPerson = personAgentFactory.create(person,initialLocation);
			personAgents.put(newPerson.getPerson().getId(),newPerson);
			beamServices.getScheduler().scheduleCallBacks(beamServices.getScheduler().createCallBackMethod(0.0, newPerson, "Begin", this.getClass()));
		}
		
		/*
		 * Look at routes
		 */
		beamServices.getMatsimServices().getTripRouterProvider().get().calcRoute("", null, null, 0.0, null);
	}

	public PersonAgent getPersonAgentById(Id<Person> personId) {
		return personAgents.get(personId);
	}
}
