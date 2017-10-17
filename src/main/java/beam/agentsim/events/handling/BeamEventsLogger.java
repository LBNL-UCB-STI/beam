package beam.agentsim.events.handling;

import beam.agentsim.events.LoggerLevels;
import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.*;

import static beam.agentsim.events.LoggerLevels.OFF;

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

    // create multimap to store key and values
    Multimap<Class, String> eventFieldsToDropWhenShort = ArrayListMultimap.create();
    Multimap<Class, String> eventFieldsToAddWhenVerbose = ArrayListMultimap.create();
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

        //filter according loggerLevel
        if (this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel().equals("")){
            defaultLevel = OFF;
        }else{
            defaultLevel = LoggerLevels.valueOf(this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel());
            eventsToLog.addAll(getAllLoggableEvents());
        }
        overrideDefaultLoggerSetup();
        shortLoggerSetup();
        verboseLoggerSetup();

        //Write events for filter LoggingLevels();
        createEventsWriters();
    }

    public void iterationEnds() {
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
        return getLoggingLevel(event.getClass());
    }
    public LoggerLevels getLoggingLevel(Class clazz) {
        if (levels.containsKey(clazz)){
            return levels.get(clazz);
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
        //Remove attribute from each event class for SHORT logger level
        if(getLoggingLevel(event) == LoggerLevels.SHORT && eventFieldsToDropWhenShort.containsKey(event.getClass())){
            eventFields = (List) eventFieldsToDropWhenShort.get(event.getClass());
            // iterate through the key set
            for (String key : eventFields) {
                attributes.remove(key);
            }
        }
        //Add attribute from each event class for VERBOSE logger level
        else if(getLoggingLevel(event) == LoggerLevels.VERBOSE && eventFieldsToAddWhenVerbose.containsKey(event.getClass())){
            eventFields = (List) eventFieldsToAddWhenVerbose.get(event.getClass());
            // iterate through the key set
            for (String key : eventFields) {
//                attributes.putAll(event.getVer);
            }
        }
        return attributes;
    }

    public void overrideDefaultLoggerSetup() {
        Class<?> theClass = null;

        for(String classAndLevel : beamServices.beamConfig().beam().outputs().overrideLoggingLevels().split(",")){
            String[] splitClassLevel = classAndLevel.split(":");
            String classString = splitClassLevel[0].trim();
            String levelString = splitClassLevel[1].trim();
            LoggerLevels theLevel = LoggerLevels.valueOf(levelString);
            try {
                theClass = Class.forName(classString);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                DebugLib.stopSystemAndReportInconsistency("Logging class name '" + theClass.getCanonicalName() + "' is not a valid class, use fully qualified class names (e.g. .");
            }
            setLoggingLevel(theClass, theLevel);
            if (theLevel != OFF){
                eventsToLog.add(theClass);
            } else if (theLevel == OFF){
                eventsToLog.remove(theClass);
            }
        }
    }

    public void shortLoggerSetup() {
//        eventFieldsToDropWhenShort.put(PathTraversalEvent.class, PathTraversalEvent.ATTRIBUTE_VIZ_DATA);
//        eventFieldsToDropWhenShort.put(PathTraversalEvent.class, PathTraversalEvent.ATTRIBUTE_LINK_IDS);
//        eventFieldsToDropWhenShort.put(PathTraversalEvent.class, PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME);
//        eventFieldsToDropWhenShort.put(ActivityEndEvent.class,ActivityEndEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(ActivityEndEvent.class,ActivityEndEvent.ATTRIBUTE_ACTTYPE);
//        eventFieldsToDropWhenShort.put(PersonDepartureEvent.class,PersonDepartureEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(PersonDepartureEvent.class,PersonDepartureEvent.ATTRIBUTE_LEGMODE);
//        eventFieldsToDropWhenShort.put(VehicleEntersTrafficEvent.class,VehicleEntersTrafficEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(VehicleEntersTrafficEvent.class,VehicleEntersTrafficEvent.ATTRIBUTE_NETWORKMODE);
//        eventFieldsToDropWhenShort.put(VehicleEntersTrafficEvent.class,VehicleEntersTrafficEvent.ATTRIBUTE_POSITION);
//        eventFieldsToDropWhenShort.put(LinkLeaveEvent.class,LinkLeaveEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(LinkEnterEvent.class,LinkEnterEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(VehicleLeavesTrafficEvent.class,VehicleLeavesTrafficEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(VehicleLeavesTrafficEvent.class,VehicleLeavesTrafficEvent.ATTRIBUTE_NETWORKMODE);
//        eventFieldsToDropWhenShort.put(VehicleLeavesTrafficEvent.class,VehicleLeavesTrafficEvent.ATTRIBUTE_POSITION);
//        eventFieldsToDropWhenShort.put(PersonArrivalEvent.class,PersonArrivalEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(ActivityStartEvent.class,ActivityStartEvent.ATTRIBUTE_LINK);
//        eventFieldsToDropWhenShort.put(ActivityStartEvent.class,ActivityStartEvent.ATTRIBUTE_ACTTYPE);
    }

    public void verboseLoggerSetup() {
//        eventFieldsToAddWhenVerbose.put(ModeChoiceEvent.class,ModeChoiceEvent.VERBOSE_ATTRIBUTE_EXP_MAX_UTILITY);
//        eventFieldsToAddWhenVerbose.put(ModeChoiceEvent.class,ModeChoiceEvent.VERBOSE_ATTRIBUTE_LOCATION);
    }


}
