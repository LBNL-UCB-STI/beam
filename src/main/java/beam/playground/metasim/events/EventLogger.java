package beam.playground.metasim.events;

import java.util.HashMap;
import java.util.HashSet;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;

import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.events.TransitionEvent;
import beam.playground.metasim.services.BeamServices;

public class EventLogger {

	HashMap<String, Integer> levels = new HashMap<>();
	private int defaultLevel = 0;
	private int writeEVEventsInterval = 1;
	private HashSet<Class> controlEventTypesWithLogger=new HashSet<>();
	private BeamServices beamServices;
	private MatsimServices services;

	@Inject
	public EventLogger(MatsimServices services, BeamServices beamServices) {
		this.services = services;
		this.beamServices = beamServices;

		// TODO redesign this based on how we redesign the configuration form
		/*
		for (String key : params.keySet()) {
			if (key.equalsIgnoreCase("Default.level")) {
				setDefaultLevel(Integer.parseInt(params.get(key)));
			} else if (key.contains(".level")) {
				setLoggingLevel(key.replaceAll(".level", ""), Integer.parseInt(params.get(key)));
			} else if (key.contains("writeEVEventsInterval")) {
				setWriteEVEventsInterval(Integer.parseInt(params.get(key)));
			} else {
				DebugLib.stopSystemAndReportInconsistency(
						"parameter: '" + key + "' unknown in module " + BeamConfigGroup.GROUP_NAME);
			}

		}
		*/
		getControlEventTypesWithLogger().add(ActionEvent.class);
		getControlEventTypesWithLogger().add(TransitionEvent.class);
	}

	private void setLoggingLevel(String eventType, int level) {
		levels.put(eventType, level);
	}

	public Integer getLoggingLevel(Event event) {
		if (levels.containsKey(event.getEventType())) {
			return levels.get(event.getEventType());
		} else {
			return getDefaultLevel();
		}
	}

	public void processEvent(final Event event) {
		services.getEvents().processEvent(event);
	}

	public int getWriteEVEventsInterval() {
		return writeEVEventsInterval;
	}

	private void setWriteEVEventsInterval(int writeEVEventsInterval) {
		this.writeEVEventsInterval = writeEVEventsInterval;
	}

	public int getDefaultLevel() {
		return defaultLevel;
	}

	private void setDefaultLevel(int defaultLevel) {
		this.defaultLevel = defaultLevel;
	}

	public HashSet<Class> getControlEventTypesWithLogger() {
		return controlEventTypesWithLogger;
	}

}
