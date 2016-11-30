package beam.sim;

import com.google.inject.Inject;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.ActivityEngine;

public class AdaptedActivityEngine extends ActivityEngine {

	@Inject
	public AdaptedActivityEngine(EventsManager eventsManager) {
		super(eventsManager);
	}

	
	@Override
	public boolean handleActivity(MobsimAgent agent) {
		return super.handleActivity(agent);
	}
	
}
