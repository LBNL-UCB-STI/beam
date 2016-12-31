package beam.playground.metasim.events;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.MatsimServices;

import beam.parking.lib.DebugLib;
import beam.playground.metasim.services.BeamServices;

public class BeamEventLogger {

	HashMap<Class<?>, Integer> levels = new HashMap<>();
	private HashSet<Class<?>> eventsToLog=new HashSet<>();
	private BeamServices beamServices;
	private MatsimServices services;

	public BeamEventLogger(BeamServices beamServices, MatsimServices services) {
		this.beamServices = beamServices;
		this.services = services;
		
		Map<String,String> params = beamServices.getBeamEventLoggerConfigGroup().getParams();
		
		if(beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel() > 0){
			// All classes are logged by default
			eventsToLog.addAll(beamServices.getBeamEventLoggerConfigGroup().getAllLoggableEvents());
		}

		Class<?> theClass = null;
		for (String key : params.keySet()) {
			if(key.contains(".level") && !key.equals("Default.level")) {
				Integer loggingLevel = Integer.parseInt(params.get(key));
				try {
					theClass = Class.forName(key.replaceAll(".level", ""));
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
					DebugLib.stopSystemAndReportInconsistency("Logging class name '"+theClass.getCanonicalName()+"' is not a valid class, use fully qualified class names (e.g. .");
				}
				setLoggingLevel(theClass, loggingLevel);
				if(beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel() <= 0 & loggingLevel > 0){
					eventsToLog.add(theClass);
				}else if(beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel() > 0 & loggingLevel <= 0){
					eventsToLog.remove(theClass);
				}
			}
		}
	}

	private void setLoggingLevel(Class<?> eventType, int level) {
		levels.put(eventType, level);
	}

	public Integer getLoggingLevel(Event event) {
		if (levels.containsKey(event.getClass())) {
			return levels.get(event.getClass());
		} else {
			return this.beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel();
		}
	}

	public void processEvent(final Event event) {
		services.getEvents().processEvent(event);
	}

	public boolean logEventsOfClass(Class<?> clazz) {
		//TODO in future this is where fine tuning logging based on level number could occur (e.g. info versus debug)
		return eventsToLog.contains(clazz);
	}
	
	public HashSet<Class<?>> getAllEventsToLog(){
		return eventsToLog;
	}

}

