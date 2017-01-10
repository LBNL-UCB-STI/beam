package beam.playground.metasim.agents;

import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.utils.objectattributes.attributable.Attributes;

import com.google.inject.Inject;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.choice.models.ChoiceModel;
import beam.playground.metasim.agents.plans.AgentWithPlans;
import beam.playground.metasim.agents.plans.BeamPlan;
import beam.playground.metasim.agents.plans.BeamPlanFactory;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public class PersonAgent extends BeamAgent.Default implements MobileAgent, AgentWithPlans {
	Coord location;
	Person person;
	BeamPlan beamPlan;
	
	public PersonAgent(Person person, Coord location, BeamServices beamServices, BeamPlanFactory beamPlanFactory){
		super(Id.create(person.getId().toString(), BeamAgent.class), beamServices);
		this.person = person;
		this.beamPlan = beamPlanFactory.create(this,person.getSelectedPlan());
	}

	@Override
	public ChoiceModel getChoiceModel(Action action) {
		return beamPlan.getTransitionSelectorFor(action);
	}

	@Override
	public Coord getLocation() {
		return location;
	}

	@Override
	public Boolean hasVehicleAvailable(Class<?> vehicleType) {
		return true;
	}

	@Override
	public Person getPerson() {
		return person;
	}

	@Override
	public BeamPlan getBeamPlan() {
		return null;
	}

}
