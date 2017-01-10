package beam.playground.metasim.agents.choice.models.schedulers;

import java.util.LinkedList;
import java.util.List;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.scheduler.ActionScheduler;
import beam.playground.metasim.services.BeamServices;

public class EnterStateInActivityActionScheduler extends ActionScheduler.Default{

	public EnterStateInActivityActionScheduler(BeamServices beamServices) {
		super(beamServices);
	}
	@Override
	public List<ActionCallBack> scheduleNextAction(BeamAgent agent, Transition callingTransition) {
		// We are entering "In Activity" so we therefore assume this is a PersonAgent and access their BeamPlan
		PersonAgent personAgent = (PersonAgent)agent;
		// Fir
//		Double activityEndTime = personAgent.getBeamPlan().
		return new LinkedList<ActionCallBack>();
	}

}
