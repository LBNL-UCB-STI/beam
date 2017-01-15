package beam.playground.metasim.agents.states;

import java.util.LinkedList;
import java.util.List;

import beam.playground.metasim.BeamMode;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.services.BeamServices;

public class EnterExitChoosingMode extends StateEnterExitListener.Default {

	public EnterExitChoosingMode(BeamServices beamServices) {
		super(beamServices);
	}

	@Override
	public List<ActionCallBack> notifyOfStateEntry(BeamAgent agent) {
		return beamServices.getScheduler().createCallBackMethod(beamServices.getScheduler().getNow() + 60.0, agent, "StartTrip", this.getClass());
	}

	@Override
	public List<ActionCallBack> notifyOfStateExit(BeamAgent agent) {
		PersonAgent person = (PersonAgent)agent;
		double timeToWalk = 0.0;
		LinkedList<ActionCallBack> callback = new LinkedList<>();
		if(person.getChosenMode() == BeamMode.WALK){
			timeToWalk = beamServices.getLocationalServices().getTripInformation(person.getCurrentOrNextLeg(),BeamMode.WALK).getTripTravelTime();
			callback.addAll(beamServices.getScheduler().createCallBackMethod(beamServices.getScheduler().getNow() + timeToWalk, agent, "ArriveFromWalk", this.getClass()));
		}
		return callback;
	}

}
