package beam.events.scoring;

import org.matsim.core.events.handler.EventHandler;

public interface ParkingScoreEventHandler extends EventHandler {
	public void handleEvent(ParkingScoreEvent event);
}
