package beam.events.scoring;

import org.matsim.core.events.handler.EventHandler;

public interface ChangePlugOverheadScoreEventHandler extends EventHandler {
	public void handleEvent(ChangePlugOverheadScoreEvent event);
}
