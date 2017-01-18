package beam.playground.metasim.agents.states;

import java.util.LinkedList;
import java.util.List;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.services.BeamServices;

public interface StateEnterExitListener {
	List<? extends ActionCallBack> notifyOfStateEntry(BeamAgent agent);
	List<? extends ActionCallBack> notifyOfStateExit(BeamAgent agent);
	
	public class Default implements StateEnterExitListener{
		BeamServices beamServices;
		
		@Inject
		public Default(BeamServices beamServices){
			this.beamServices = beamServices;
		}

		@Override
		public List<? extends ActionCallBack> notifyOfStateEntry(BeamAgent agent) {
			return new LinkedList<ActionCallBack>();
		}

		@Override
		public List<? extends ActionCallBack> notifyOfStateExit(BeamAgent agent) {
			return new LinkedList<ActionCallBack>();
		}
		
	}
}
