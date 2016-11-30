package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface ArrivalChargingDecisionEventHandler extends EventHandler{
	public void handleEvent(ArrivalChargingDecisionEvent event);
}
