package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface NestedLogitDecisionEventHandler extends EventHandler{
	public void handleEvent(NestedLogitDecisionEvent event);
}
