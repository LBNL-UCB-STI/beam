package beam.events;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.events.TransitionEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.config.Config;

import beam.EVGlobalData;
import beam.events.scoring.ChangePlugOverheadScoreEvent;
import beam.events.scoring.ChargingCostScoreEvent;
import beam.events.scoring.LegTravelTimeScoreEvent;
import beam.events.scoring.ParkingScoreEvent;
import beam.events.scoring.RangeAnxietyScoreEvent;
import beam.parking.lib.DebugLib;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;

public class EventLogger {

	HashMap<String, Integer> levels = new HashMap<>();
	private int defaultLevel = 0;
	private int writeEVEventsInterval = 1;
	private HashSet<Class> controlEventTypesWithLogger=new HashSet<>();

	public EventLogger(Config config) {
		if (config.getModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_LOGGER_MODULE_NAME) != null) {

			Map<String, String> params = config.getModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_LOGGER_MODULE_NAME).getParams();

			for (String key : params.keySet()) {
				if (key.equalsIgnoreCase("Default.level")) {
					setDefaultLevel(Integer.parseInt(params.get(key)));
				} else if (key.contains(".level")) {
					setLoggingLevel(key.replaceAll(".level", ""), Integer.parseInt(params.get(key)));
				} else if (key.contains("writeEVEventsInterval")) {
					setWriteEVEventsInterval(Integer.parseInt(params.get(key)));
				} else {
					DebugLib.stopSystemAndReportInconsistency(
							"parameter: '" + key + "' unknown in module " + EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_LOGGER_MODULE_NAME);
				}

			}

			// as this is not a registered MATSim module, removing it from
			// config - otherwise this might create problems
			config.removeModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_LOGGER_MODULE_NAME);
		}

		getControlEventTypesWithLogger().add(ArrivalChargingDecisionEvent.class);
		getControlEventTypesWithLogger().add(DepartureChargingDecisionEvent.class);
		getControlEventTypesWithLogger().add(BeginChargingSessionEvent.class);
		getControlEventTypesWithLogger().add(EndChargingSessionEvent.class);
		getControlEventTypesWithLogger().add(ParkWithoutChargingEvent.class);
		getControlEventTypesWithLogger().add(PreChargeEvent.class);
		getControlEventTypesWithLogger().add(ReassessDecisionEvent.class);
		getControlEventTypesWithLogger().add(ChangePlugOverheadScoreEvent.class);
		getControlEventTypesWithLogger().add(ChargingCostScoreEvent.class);
		getControlEventTypesWithLogger().add(LegTravelTimeScoreEvent.class);
		getControlEventTypesWithLogger().add(ParkingScoreEvent.class);
		getControlEventTypesWithLogger().add(RangeAnxietyScoreEvent.class);
		getControlEventTypesWithLogger().add(InternalRangeAnxityEvent.class);
		getControlEventTypesWithLogger().add(UnplugEvent.class);
		getControlEventTypesWithLogger().add(NestedLogitDecisionEvent.class);
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
		//if (EVGlobalData.data.controler.getIterationNumber() % getWriteEVEventsInterval() == 0) {
		//	if ((levels.containsKey(event.getEventType()) && levels.get(event.getEventType()) > 0)
		//			|| (!levels.containsKey(event.getEventType()) && getDefaultLevel() > 0)) {
				EVGlobalData.data.controler.getEvents().processEvent(event);
		//	}
		//}
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
