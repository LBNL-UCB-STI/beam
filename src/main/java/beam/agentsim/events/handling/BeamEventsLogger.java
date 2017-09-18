package beam.agentsim.events.handling;

import beam.agentsim.events.LoggerLevels;
import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.*;

/**
 * BEAM
 */

public class BeamEventsLogger {
    private final EventsManager eventsManager;
    private ArrayList<BeamEventsWriterBase> writers = new ArrayList<>();

    HashMap<Class<?>, LoggerLevels> levels = new HashMap<>();
    LoggerLevels defaultLevel;

    private HashSet<Class<?>> allLoggableEvents = new HashSet<>(), eventsToLog=new HashSet<>();
    private BeamServices beamServices;
    private String eventsFileFormats;
    private ArrayList<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();
    private HashMap<Class, List<String>> eventFieldsToDropWhenShort = new HashMap<Class, List<String>>();
    private HashMap<Class, List<String>> eventFieldsToAddWhenVerbose = new HashMap<Class, List<String>>();
    private List<String> eventFields = null;

    public BeamEventsLogger(BeamServices beamServices, EventsManager eventsManager) {

        this.beamServices = beamServices;
        this.eventsManager = eventsManager;
        setEventsFileFormats();

        // Registry of BEAM events that can be logged by BeamEventLogger
        allLoggableEvents.add(PathTraversalEvent.class);
        allLoggableEvents.add(ModeChoiceEvent.class);

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
        //Adding attribute which should be removed when the logger level SHORT

        eventFields = new ArrayList<String>();
        eventFields.add(PathTraversalEvent.ATTRIBUTE_VIZ_DATA);
        eventFields.add(PathTraversalEvent.ATTRIBUTE_LINK_IDS);
        eventFields.add(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME);
        eventFieldsToDropWhenShort.put(PathTraversalEvent.class, eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(ActivityEndEvent.ATTRIBUTE_LINK);
        eventFields.add(ActivityEndEvent.ATTRIBUTE_PERSON);
        eventFieldsToDropWhenShort.put(ActivityEndEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(PersonDepartureEvent.ATTRIBUTE_LINK);
        eventFields.add(PersonDepartureEvent.ATTRIBUTE_PERSON);
        eventFieldsToDropWhenShort.put(PersonDepartureEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);
        eventFieldsToDropWhenShort.put(PersonEntersVehicleEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(VehicleEntersTrafficEvent.ATTRIBUTE_LINK);
        eventFields.add(VehicleEntersTrafficEvent.ATTRIBUTE_NETWORKMODE);
        eventFieldsToDropWhenShort.put(VehicleEntersTrafficEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(LinkLeaveEvent.ATTRIBUTE_LINK);
        eventFieldsToDropWhenShort.put(LinkLeaveEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(LinkEnterEvent.ATTRIBUTE_LINK);
        eventFieldsToDropWhenShort.put(LinkEnterEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(VehicleLeavesTrafficEvent.ATTRIBUTE_LINK);
        eventFields.add(VehicleLeavesTrafficEvent.ATTRIBUTE_NETWORKMODE);
        eventFields.add(VehicleLeavesTrafficEvent.ATTRIBUTE_POSITION);
        eventFieldsToDropWhenShort.put(VehicleLeavesTrafficEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(PersonArrivalEvent.ATTRIBUTE_LINK);
        eventFieldsToDropWhenShort.put(PersonArrivalEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(ActivityStartEvent.ATTRIBUTE_LINK);
        eventFieldsToDropWhenShort.put(ActivityStartEvent.class,eventFields);
        //Adding attribute which should be added when the logger level VERBOSE
        eventFields = new ArrayList<String>();
        eventFields.add(ModeChoiceEvent.VERBOSE_ATTRIBUTE_ALTERNATIVES);
        eventFieldsToAddWhenVerbose.put(ModeChoiceEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(ActivityEndEvent.ATTRIBUTE_FACILITY);
        eventFieldsToAddWhenVerbose.put(ActivityEndEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(PathTraversalEvent.ATTRIBUTE_VIZ_DATA);
        eventFieldsToAddWhenVerbose.put(PathTraversalEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(ActivityStartEvent.ATTRIBUTE_FACILITY);
        eventFieldsToAddWhenVerbose.put(ActivityStartEvent.class,eventFields);
        eventFields = new ArrayList<String>();
        eventFields.add(PathTraversalEvent.ATTRIBUTE_VIZ_DATA);
        eventFieldsToAddWhenVerbose.put(PathTraversalEvent.class,eventFields);

        if (!this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel().equals("")&&!this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel().equals(LoggerLevels.OFF)) {
            defaultLevel = LoggerLevels.valueOf(this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel());
            System.out.println("defaultLevel=--------------"+defaultLevel);
            eventsToLog.addAll(getAllLoggableEvents());
        }
        //        filterLoggingLevels();
        createEventsWriters();
    }

    public void interationEnds() {
        for (BeamEventsWriterBase writer : this.writers) {
            writer.closeFile();
            this.eventsManager.removeHandler(writer);
        }
        this.writers.clear();
    }

    public void createEventsWriters() {
        int iterationNumber = this.beamServices.matsimServices().getIterationNumber();
        boolean writeThisIteration = (this.beamServices.beamConfig().beam().outputs().writeEventsInterval() > 0) && (iterationNumber % this.beamServices.beamConfig().beam().outputs().writeEventsInterval() == 0);
        if (writeThisIteration) {
            this.beamServices.matsimServices().getControlerIO().createIterationDirectory(iterationNumber);
            String eventsFileBasePath = this.beamServices.matsimServices().getControlerIO().getIterationFilename(iterationNumber, "events");

            for (BeamEventsFileFormats fmt : this.eventsFileFormatsArray) {
                BeamEventsWriterBase newWriter = null;
                if (this.beamServices.beamConfig().beam().outputs().explodeEventsIntoFiles()) {
                    for (Class<?> eventTypeToLog : getAllEventsToLog()) {
                        newWriter = createEventWriterForClassAndFormat(eventsFileBasePath, eventTypeToLog, fmt);
                        writers.add(newWriter);
                        eventsManager.addHandler(newWriter);
                    }
                } else {
                    newWriter = createEventWriterForClassAndFormat(eventsFileBasePath, null, fmt);
                    writers.add(newWriter);
                    eventsManager.addHandler(newWriter);
                }
            }
        }
    }
    public BeamEventsWriterBase createEventWriterForClassAndFormat(String eventsFilePathBase, Class<?> theClass, BeamEventsFileFormats fmt){
        if (fmt == BeamEventsFileFormats.xml || fmt == BeamEventsFileFormats.xmlgz) {
            String path = eventsFilePathBase + ((fmt == BeamEventsFileFormats.xml) ? ".xml" : ".xml.gz");
            return new BeamEventsWriterXML(path, this, this.beamServices, theClass);
        }else if (fmt == BeamEventsFileFormats.csv || fmt == BeamEventsFileFormats.csvgz){
            String path = eventsFilePathBase + ((fmt == BeamEventsFileFormats.csv) ? ".csv" : ".csv.gz");
            return new BeamEventsWriterCSV(path, this, this.beamServices, theClass);
        }
        return null;
    }

    private void setLoggingLevel(Class<?> eventType, LoggerLevels level) {
        levels.put(eventType, level);
    }

    //Logging control code changed return type from int to String
    public LoggerLevels getLoggingLevel(Event event) {
        if (levels.containsKey(event.getClass())) {
            return levels.get(event.getClass());
        } else {
            return defaultLevel;
        }
    }

    public boolean shouldLogThisEventType(Class<? extends Event> aClass) {
        //TODO in future this is where fine tuning logging based on level number could occur (e.g. info versus debug)
        return eventsToLog.contains(aClass);
    }

    public HashSet<Class<?>> getAllEventsToLog(){
        return eventsToLog;
    }

    public HashSet<Class<?>> getAllLoggableEvents(){
        return allLoggableEvents;
    }

    public void setEventsFileFormats() {
        String eventsFilePath = "";
        BeamEventsFileFormats fmt = null;
        this.eventsFileFormats = this.beamServices.beamConfig().beam().outputs().eventsFileOutputFormats();
        this.eventsFileFormatsArray.clear();
        for(String format : eventsFileFormats.split(",")){
            if(format.toLowerCase().equals("xml")){
                fmt = BeamEventsFileFormats.xml;
            }else if(format.toLowerCase().equals("xml.gz")){
                fmt = BeamEventsFileFormats.xmlgz;
            }else if(format.toLowerCase().equals("csv")){
                fmt = BeamEventsFileFormats.csv;
            }else if(format.toLowerCase().equals("csv.gz")){
                fmt = BeamEventsFileFormats.csvgz;
            }
            this.eventsFileFormatsArray.add(fmt);
        }
    }

    public Map<String,String> getAttributes(Event event) {
        Map<String,String> attributes = event.getAttributes();
        if(getLoggingLevel(event) == LoggerLevels.SHORT && eventFieldsToDropWhenShort.containsKey(event.getClass())){
            eventFields = eventFieldsToDropWhenShort.get(event.getClass());
            for(String key:eventFields){
                attributes.remove(key);
            }
        }else if(getLoggingLevel(event) == LoggerLevels.VERBOSE && eventFieldsToAddWhenVerbose.containsKey(event.getClass())){
            eventFields = eventFieldsToAddWhenVerbose.get(event.getClass());
            for(String key:eventFields){
                attributes.put(key,"DUMMY DATA");
            }
        }
        return attributes;
    }

    public void filterLoggingLevels() {
        //TODO re-implement filter on logging level for individual event types
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

}
