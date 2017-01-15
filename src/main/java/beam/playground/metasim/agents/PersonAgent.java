package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.choice.models.ChoiceModel;
import beam.playground.metasim.agents.plans.AgentWithPlans;
import beam.playground.metasim.agents.plans.BeamPlan;
import beam.playground.metasim.agents.plans.BeamPlanFactory;
import beam.playground.metasim.agents.plans.PlanTracker;
import beam.playground.metasim.services.BeamServices;

public class PersonAgent extends BeamAgent.Default implements MobileAgent, AgentWithPlans {
	Coord location;
	Person person;
	BeamPlan beamPlan;
	PlanTracker planTracker;
	Link nearestLink;
	
	public PersonAgent(Person person, Coord location, BeamServices beamServices, BeamPlanFactory beamPlanFactory){
		super(Id.create(person.getId().toString(), BeamAgent.class), beamServices);
		this.person = person;
		this.location = location;
		this.nearestLink = beamServices.getLocationalServices().getNearestRoadLink(location);
		this.beamPlan = beamPlanFactory.create(this,person.getSelectedPlan());
		this.planTracker = new PlanTracker(beamPlan);
	}

	@Override
	public ChoiceModel getChoiceModel(Action action) {
		return beamPlan.getChoiceModelFor(action);
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
	
	@Override
	public Activity getCurrentOrNextActivity(){
		return planTracker.getCurrentOrNextActivity();
	}
	
	@Override
	public Leg getCurrentOrNextLeg(){
		return planTracker.getCurrentOrNextLeg();
	}

	@Override
	public PlanTracker getPlanTracker() {
		return planTracker;
	}

	@Override
	public Link getNearestLink() {
		return nearestLink;
	}

}
