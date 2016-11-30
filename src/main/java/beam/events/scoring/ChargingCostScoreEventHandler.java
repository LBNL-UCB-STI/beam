package beam.events.scoring;

import org.matsim.core.events.handler.EventHandler;

public interface ChargingCostScoreEventHandler extends EventHandler {
	public void handleEvent(ChargingCostScoreEvent event);
}
