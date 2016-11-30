package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface BeginChargingSessionEventHandler extends EventHandler {
	public void handleEvent(BeginChargingSessionEvent event);
}
