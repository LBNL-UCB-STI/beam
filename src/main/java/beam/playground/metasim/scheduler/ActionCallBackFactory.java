package beam.playground.metasim.scheduler;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public interface ActionCallBackFactory {

	ActionCallBack.Default create(double time, double priority, BeamAgent targetAgent, String actionName, Double now,
			Transition callingTransition);
	
	public class Default implements ActionCallBackFactory {
		private final Provider<BeamServices> beamServicesProvider;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider){
			this.beamServicesProvider = beamServicesProvider;
		}

		@Override
		public ActionCallBack.Default create(double time, double priority, BeamAgent targetAgent, String actionName, Double now,
				Transition callingTransition) {
			return new ActionCallBack.Default(time, priority, targetAgent, actionName, now, callingTransition, beamServicesProvider.get());
		}

	}
}
