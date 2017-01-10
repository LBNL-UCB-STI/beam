package beam.playground.metasim.scheduler;

import java.util.LinkedList;
import java.util.List;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public interface ActionScheduler {
	List<ActionCallBack> scheduleNextAction(BeamAgent agent,Transition callingTransition);

	public class Default implements ActionScheduler{
		protected BeamServices beamServices;
		
		public Default(BeamServices beamServices){
			this.beamServices = beamServices;
		}

		@Override
		public List<ActionCallBack> scheduleNextAction(BeamAgent agent, Transition callingTransition) {
			return new LinkedList<ActionCallBack>();
		}
	}
}
