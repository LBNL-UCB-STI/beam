package beam.playground.metasim.events;

import org.matsim.core.events.handler.EventHandler;

public interface ActionCallBackEventHandler extends EventHandler{
	public void handleEvent(ActionCallBackScheduleEvent event);
}
