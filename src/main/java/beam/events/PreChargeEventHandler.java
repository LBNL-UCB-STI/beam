package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface PreChargeEventHandler extends EventHandler{
	public void handleEvent(PreChargeEvent event);
}
