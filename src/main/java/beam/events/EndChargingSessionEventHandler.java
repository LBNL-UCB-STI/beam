package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface EndChargingSessionEventHandler extends EventHandler{
	public void handleEvent(EndChargingSessionEvent event);
}
