package beam.playground.metasim.agents.states;

import java.util.LinkedList;
import java.util.List;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.services.BeamServices;

public class EnterExitInActivity extends StateEnterExitListener.Default {

	public EnterExitInActivity(BeamServices beamServices) {
		super(beamServices);
	}

	@Override
	public List<ActionCallBack> notifyOfStateEntry(BeamAgent agent) {
		PersonAgent personAgent = (PersonAgent)agent;
		Double activityEndTime = (personAgent.getPlanTracker().getCurrentOrNextActivity()==null) ? -1.0 : personAgent.getPlanTracker().getCurrentOrNextActivity().getEndTime();
		//TODO we need a robust way of handling end_time that is blank, for now just assume 12 hours of activity
		if(activityEndTime < beamServices.getScheduler().getNow())activityEndTime = beamServices.getScheduler().getNow() + 12.0*3600.0;
		return beamServices.getScheduler().createCallBackMethod(activityEndTime, agent, "EndActivity", this.getClass());
	}

	@Override
	public List<ActionCallBack> notifyOfStateExit(BeamAgent agent) {
		return new LinkedList<>();
	}

}
