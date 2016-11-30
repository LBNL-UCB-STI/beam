package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface ParkWithoutChargingEventHandler extends EventHandler{
	public void handleEvent(ParkWithoutChargingEvent event);
}
