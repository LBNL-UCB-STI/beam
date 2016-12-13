package beam.playground.metasim.agents;

import java.util.Collection;
import java.util.LinkedHashSet;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;

import beam.playground.metasim.services.BeamServices;
import beam.playground.metasim.states.BaseState;
import beam.playground.metasim.states.State;

public class BeamAgentPopulation implements StartupListener{
	LinkedHashSet<BeamAgent> beamAgents;
	@Inject BeamServices beamServices;

	@Override
	public void notifyStartup(StartupEvent event) {
		beamAgents = new LinkedHashSet<BeamAgent>();
		Scenario scenario = event.getServices().getScenario();
		State startState = new BaseState("start");
		for(Person person : scenario.getPopulation().getPersons().values()){
			Coord initialLocation = ((Activity)person.getPlans().get(0).getPlanElements().get(0)).getCoord();
			beamAgents.add(new PersonAgent(person.getId(),startState,initialLocation));
		}
	}

	public Collection<BeamAgent> getAgents() {
		return beamAgents;
	}
}
