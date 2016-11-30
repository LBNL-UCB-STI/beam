package beam.events.scoring;

import org.matsim.core.events.handler.EventHandler;

public interface LegTravelTimeScoreEventHandler extends EventHandler {
	public void handleEvent(LegTravelTimeScoreEvent event);
}
