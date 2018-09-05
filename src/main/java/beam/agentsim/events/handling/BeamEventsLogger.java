package beam.agentsim.events.handling;

import beam.agentsim.events.LeavingParkingEvent;
import beam.agentsim.events.LoggerLevels;
import beam.agentsim.events.ParkEvent;
import beam.agentsim.events.RefuelEvent;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static beam.agentsim.events.LoggerLevels.OFF;

class BeamEventsLogger {

    private final EventsManager eventsManager;
    private final MatsimServices matsimServices;
    private final LoggerLevels defaultLevel;
    private final BeamServices beamServices;

    private final Multimap<Class, String> eventFieldsToDropWhenShort = ArrayListMultimap.create();
    private final List<BeamEventsWriterBase> writers = new ArrayList<>();
    private final Map<Class<?>, LoggerLevels> levels = new HashMap<>();
    private final Set<Class<?>> allLoggableEvents = new HashSet<>();
    private final Set<Class<?>> eventsToLog = new HashSet<>();
    private final List<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();

    BeamEventsLogger(BeamServices beamServices, MatsimServices matsimServices, EventsManager eventsManager) {
        this.beamServices = beamServices;
        this.matsimServices = matsimServices;
        this.eventsManager = eventsManager;

        setEventsFileFormats();

        registryBeamLoggableEvents();

        registryMATSimLoggableEvents();

        defaultLevel = getDefaultLevel();

        if (defaultLevel != OFF) {
            eventsToLog.addAll(getAllLoggableEvents());
        }

        overrideDefaultLoggerSetup();

        shortLoggerSetup();

        verboseLoggerSetup();

        createEventsWriters();
    }

    private void registryBeamLoggableEvents() {
        allLoggableEvents.add(beam.agentsim.events.PathTraversalEvent.class);
        allLoggableEvents.add(beam.agentsim.events.ModeChoiceEvent.class);
        allLoggableEvents.add(beam.agentsim.events.ReplanningEvent.class);
        allLoggableEvents.add(ParkEvent.class);
        allLoggableEvents.add(LeavingParkingEvent.class);
        allLoggableEvents.add(RefuelEvent.class);
    }

    private void registryMATSimLoggableEvents() {
        allLoggableEvents.add(org.matsim.api.core.v01.events.ActivityEndEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.PersonDepartureEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.PersonEntersVehicleEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.VehicleEntersTrafficEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.LinkLeaveEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.LinkEnterEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.PersonLeavesVehicleEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.PersonArrivalEvent.class);
        allLoggableEvents.add(org.matsim.api.core.v01.events.ActivityStartEvent.class);
    }

    private LoggerLevels getDefaultLevel() {
        final String defaultWritingLevel = beamServices.beamConfig().beam().outputs().events().defaultWritingLevel();
        return "".equals(defaultWritingLevel)
                ? OFF
                : LoggerLevels.valueOf(defaultWritingLevel);
    }

    void iterationEnds() {
        for (BeamEventsWriterBase writer : writers) {
            writer.closeFile();
            eventsManager.removeHandler(writer);
        }
        writers.clear();
    }

    private void createEventsWriters() {
        int iterationNumber = matsimServices.getIterationNumber();

        final int writeEventsInterval = beamServices.beamConfig().beam().outputs().writeEventsInterval();

        final boolean writeThisIteration = (writeEventsInterval > 0) && (iterationNumber % writeEventsInterval == 0);

        if (writeThisIteration) {
            matsimServices.getControlerIO().createIterationDirectory(iterationNumber);
            String eventsFileBasePath = matsimServices.getControlerIO().getIterationFilename(iterationNumber, "events");

            for (BeamEventsFileFormats fmt : eventsFileFormatsArray) {
                BeamEventsWriterBase newWriter = null;
                if (beamServices.beamConfig().beam().outputs().events().explodeIntoFiles()) {
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

    private BeamEventsWriterBase createEventWriterForClassAndFormat(String eventsFilePathBase,
            Class<?> theClass, BeamEventsFileFormats fmt) {
        final String path = eventsFilePathBase + "." + fmt.getSuffix();

        if (fmt == BeamEventsFileFormats.XML || fmt == BeamEventsFileFormats.XML_GZ) {
            return new BeamEventsWriterXML(path, this, beamServices, theClass);
        } else if (fmt == BeamEventsFileFormats.CSV || fmt == BeamEventsFileFormats.CSV_GZ) {
            return new BeamEventsWriterCSV(path, this, beamServices, theClass);
        }

        return null;
    }

    private void setLoggingLevel(Class<?> eventType, LoggerLevels level) {
        levels.put(eventType, level);
    }

    Multimap<Class, String> getEventFieldsToDropWhenShort() {
        return eventFieldsToDropWhenShort;
    }

    //Logging control code changed return type from int to String
    LoggerLevels getLoggingLevel(Event event) {
        return getLoggingLevel(event.getClass());
    }

    LoggerLevels getLoggingLevel(Class clazz) {
        return levels.getOrDefault(clazz, defaultLevel);
    }

    boolean shouldLogThisEventType(Class<? extends Event> aClass) {
        //TODO in future this is where fine tuning logging based on level number could occur (e.g. info versus debug)
        return eventsToLog.contains(aClass);
    }

    Set<Class<?>> getAllEventsToLog() {
        return eventsToLog;
    }

    private Set<Class<?>> getAllLoggableEvents() {
        return allLoggableEvents;
    }

    private void setEventsFileFormats() {
        eventsFileFormatsArray.clear();
        String eventsFileFormats = beamServices.beamConfig().beam().outputs().events().fileOutputFormats();
        for (String format : eventsFileFormats.split(",")) {
            BeamEventsFileFormats.from(format)
                    .ifPresent(eventsFileFormatsArray::add);
        }
    }

    Set<String> getKeysToWrite(Event event, Map<String, String> eventAttributes) {
        Set<String> keySet = new HashSet<>(eventAttributes.keySet());
        if (getLoggingLevel(event) == LoggerLevels.SHORT && eventFieldsToDropWhenShort.containsKey(event.getClass())) {
            List<String> eventFields = (List<String>) eventFieldsToDropWhenShort.get(event.getClass());
            for (String key : eventFields) {
                keySet.remove(key);
            }
        }

//        Add attribute from each event class for VERBOSE logger level
//        else if (getLoggingLevel(event) == LoggerLevels.VERBOSE && eventFieldsToAddWhenVerbose.containsKey(event.getClass())) {
//            eventFields = (List) eventFieldsToAddWhenVerbose.get(event.getClass());
//            // iterate through the key set
//            for (String key : eventFields) {
//                attributes.putAll(event.getVer);
//            }
//        }
        return keySet;
    }

    private void overrideDefaultLoggerSetup() {
        Class<?> theClass = null;

        for (String classAndLevel : beamServices.beamConfig().beam().outputs().events().overrideWritingLevels()
                .split(",")) {
            String[] splitClassLevel = classAndLevel.split(":");
            String classString = splitClassLevel[0].trim();
            String levelString = splitClassLevel[1].trim();
            LoggerLevels theLevel = LoggerLevels.valueOf(levelString);
            try {
                theClass = Class.forName(classString);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                DebugLib.stopSystemAndReportInconsistency("Logging class name '"
                        + theClass.getCanonicalName()
                        + "' is not a valid class, use fully qualified class names (e.g. .");
            }
            setLoggingLevel(theClass, theLevel);
            if (theLevel == OFF) {
                eventsToLog.remove(theClass);
            } else {
                eventsToLog.add(theClass);
            }
        }
    }

    private void shortLoggerSetup() {
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

    private void verboseLoggerSetup() {
//        eventFieldsToAddWhenVerbose.put(ModeChoiceEvent.class,ModeChoiceEvent.VERBOSE_ATTRIBUTE_EXP_MAX_UTILITY);
//        eventFieldsToAddWhenVerbose.put(ModeChoiceEvent.class,ModeChoiceEvent.VERBOSE_ATTRIBUTE_LOCATION);
    }


}
