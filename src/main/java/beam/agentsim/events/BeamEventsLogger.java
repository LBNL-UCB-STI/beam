package beam.agentsim.events;

import beam.sim.config.BeamConfig;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.controler.MatsimServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * BEAM
 */

public class BeamEventsLogger {
    HashMap<Class<?>, Integer> levels = new HashMap<>();
    private HashSet<Class<?>> allLoggableEvents = new HashSet<>(), eventsToLog=new HashSet<>();
    private BeamServices beamServices;
    private MatsimServices services;
    private BeamConfig beamConfig;
    private String eventsFileFormats;
    private ArrayList<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();

    public BeamEventsLogger(BeamServices beamServices, MatsimServices services) {

        this.beamConfig = beamServices.beamConfig();

        // Registry of BEAM events that can be logged by BeamEventLogger

        // Registry of MATSim events that can be logged by BeamEventLogger
        allLoggableEvents.add(ActivityEndEvent.class);
        allLoggableEvents.add(PersonDepartureEvent.class);
        allLoggableEvents.add(PersonEntersVehicleEvent.class);
        allLoggableEvents.add(VehicleEntersTrafficEvent.class);
        allLoggableEvents.add(LinkLeaveEvent.class);
        allLoggableEvents.add(LinkEnterEvent.class);
        allLoggableEvents.add(VehicleLeavesTrafficEvent.class);
        allLoggableEvents.add(PersonLeavesVehicleEvent.class);
        allLoggableEvents.add(PersonArrivalEvent.class);
        allLoggableEvents.add(ActivityStartEvent.class);

        if(beamConfig.beam().outputs().defaultLoggingLevel() > 0){
            // All classes are logged by default
            eventsToLog.addAll(getAllLoggableEvents());
        }

//        Class<?> theClass = null;
//        for (String key : params.keySet()) {
//            if(key.contains(".level") && !key.equals("Default.level")) {
//                Integer loggingLevel = Integer.parseInt(params.get(key));
//                try {
//                    theClass = Class.forName(key.replaceAll(".level", ""));
//                } catch (ClassNotFoundException e) {
//                    e.printStackTrace();
//                    DebugLib.stopSystemAndReportInconsistency("Logging class name '"+theClass.getCanonicalName()+"' is not a valid class, use fully qualified class names (e.g. .");
//                }
//                setLoggingLevel(theClass, loggingLevel);
//                if(beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel() <= 0 & loggingLevel > 0){
//                    eventsToLog.add(theClass);
//                }else if(beamServices.getBeamEventLoggerConfigGroup().getDefaultLevel() > 0 & loggingLevel <= 0){
//                    eventsToLog.remove(theClass);
//                }
//            }
//        }
    }

    private void setLoggingLevel(Class<?> eventType, int level) {
        levels.put(eventType, level);
    }

    public Integer getLoggingLevel(Event event) {
        if (levels.containsKey(event.getClass())) {
            return levels.get(event.getClass());
        } else {
            return this.beamConfig.beam().outputs().defaultLoggingLevel();
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
    public HashSet<Class<?>> getAllLoggableEvents(){
        return allLoggableEvents;
    }

    public void setEventsFileFormats(String eventsFileFormats) {
        this.eventsFileFormats = eventsFileFormats;
        this.eventsFileFormatsArray.clear();
        for(String format : eventsFileFormats.split(",")){
            if(format.toLowerCase().equals("xml")){
                this.eventsFileFormatsArray.add(BeamEventsFileFormats.xml);
            }else if(format.toLowerCase().equals("xml.gz")){
                this.eventsFileFormatsArray.add(BeamEventsFileFormats.xmlgz);
            }else if(format.toLowerCase().equals("csv")){
                this.eventsFileFormatsArray.add(BeamEventsFileFormats.csv);
            }else if(format.toLowerCase().equals("csv.gz")){
                this.eventsFileFormatsArray.add(BeamEventsFileFormats.csvgz);
            }
        }
    }
    public boolean logEventsInFormat(BeamEventsFileFormats fmt){
        return eventsFileFormatsArray.contains(fmt);
    }


}
