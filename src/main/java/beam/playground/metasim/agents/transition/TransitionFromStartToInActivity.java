package beam.playground.metasim.agents.transition;

import java.util.List;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.agents.choice.models.schedulers.EnterStateInActivityActionScheduler;
import beam.playground.metasim.agents.plans.AgentWithPlans;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.scheduler.ActionSchedulerFactory;

public class TransitionFromStartToInActivity extends Transition.Default {
	@Inject ActionSchedulerFactory actionSchedulerFactory;

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}
	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		PersonAgent person = (PersonAgent)agent;
//		Double timeToChooseMode = person.getPerson().getSelectedPlan();
		return beamServices.getActionSchedulerFor(EnterStateInActivityActionScheduler.class).scheduleNextAction(agent, this);
	}

}
