package beam.events.scoring;

import org.matsim.core.events.handler.EventHandler;

public interface RangeAnxietyScoreEventHandler extends EventHandler {
	public void handleEvent(RangeAnxietyScoreEvent event);
}
