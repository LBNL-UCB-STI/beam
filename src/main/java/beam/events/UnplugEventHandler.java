package beam.events;

import org.matsim.core.events.handler.EventHandler;

public interface UnplugEventHandler extends EventHandler{
	public void handleEvent(UnplugEvent event);
}
