package beam.playground.metasim.scheduler;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public class ActionCallbackFactoryImpl implements ActionCallBackFactory {
	private final Provider<BeamServices> beamServicesProvider;

	@Inject
	public ActionCallbackFactoryImpl(Provider<BeamServices> beamServicesProvider){
		this.beamServicesProvider = beamServicesProvider;
	}

	@Override
	public ActionCallBackImpl create(double time, double priority, BeamAgent targetAgent, String actionName, Double now,
			Transition callingTransition) {
		return new ActionCallBackImpl(time, priority, targetAgent, actionName, now, callingTransition, beamServicesProvider.get());
	}

}
