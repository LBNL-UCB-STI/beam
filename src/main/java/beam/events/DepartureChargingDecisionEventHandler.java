package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface DepartureChargingDecisionEventHandler extends EventHandler{
	public void handleEvent(DepartureChargingDecisionEvent event);
}
