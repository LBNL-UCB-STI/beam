package beam.playground.metasim.agents.plans;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;

import beam.playground.metasim.BeamMode;

public interface AgentWithPlans {
	
	public Person getPerson();
	public BeamPlan getBeamPlan();
	public Activity getCurrentOrNextActivity();
	public Leg getCurrentOrNextLeg();
	public PlanTracker getPlanTracker();
	public BeamMode getChosenMode();
	public void setChosenMode(BeamMode mode);

}
