package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface ReassessDecisionEventHandler extends EventHandler{
	public void handleEvent(ReassessDecisionEvent event);
}
