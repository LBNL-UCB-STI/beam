package beam.playground.events;

import org.matsim.core.events.handler.EventHandler;

public interface ActionEventHandler extends EventHandler{
	public void handleEvent(ActionEvent event);
}
