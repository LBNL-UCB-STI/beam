package beam.agentsim.events.handling;

import beam.agentsim.events.*;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;

import java.util.*;

/**
 * Logger class for BEAM events
 */
class BeamEventsLogger {

    private final EventsManager eventsManager;
    private final MatsimServices matsimServices;
    private final BeamServices beamServices;
    private final List<BeamEventsWriterBase> writers = new ArrayList<>();
    private final Set<Class<?>> eventsToLog = new HashSet<>();
    private final List<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();

    BeamEventsLogger(BeamServices beamServices, MatsimServices matsimServices, EventsManager eventsManager) {
        this.beamServices = beamServices;
        this.matsimServices = matsimServices;
        this.eventsManager = eventsManager;
        setEventsFileFormats();
        overrideDefaultLoggerSetup();
        createEventsWriters();
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
                BeamEventsWriterBase newWriter;
                newWriter = createEventWriterForClassAndFormat(eventsFileBasePath, null, fmt);
                writers.add(newWriter);
                eventsManager.addHandler(newWriter);
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

    boolean shouldLogThisEventType(Class<? extends Event> aClass) {
        //TODO in future this is where fine tuning logging based on level number could occur (e.g. info versus debug)
        return eventsToLog.contains(aClass);
    }

    Set<Class<?>> getAllEventsToLog() {
        return eventsToLog;
    }

    /**
     * Sets the event file formats
     */
    private void setEventsFileFormats() {
        eventsFileFormatsArray.clear();
        String eventsFileFormats = beamServices.beamConfig().beam().outputs().events().fileOutputFormats();
        for (String format : eventsFileFormats.split(",")) {
            BeamEventsFileFormats.from(format)
                    .ifPresent(eventsFileFormatsArray::add);
        }
    }

    Set<String> getKeysToWrite(Event event, Map<String, String> eventAttributes) {
        return eventAttributes.keySet();
    }

    /**
     * Overrides the default logger setup
     */
    private void overrideDefaultLoggerSetup() {
        Class<?> eventClass = null;
        // Generate the required event class reference based on the class name
        String eventsToWrite = beamServices.beamConfig().beam().outputs().events().eventsToWrite();
        if(!eventsToWrite.isEmpty()) {
            for (String className : beamServices.beamConfig().beam().outputs().events().eventsToWrite()
                    .split(",")) {
                switch (className) {
                    case "ActivityStartEvent":
                        eventClass = ActivityStartEvent.class;
                        break;
                    case "ActivityEndEvent":
                        eventClass = ActivityEndEvent.class;
                        break;
                    case "LeavingParkingEvent":
                        eventClass = LeavingParkingEvent.class;
                        break;
                    case "LinkEnterEvent":
                        eventClass = LinkEnterEvent.class;
                        break;
                    case "LinkLeaveEvent":
                        eventClass = LinkLeaveEvent.class;
                        break;
                    case "ModeChoiceEvent":
                        eventClass = ModeChoiceEvent.class;
                        break;
                    case "ParkEvent":
                        eventClass = ParkEvent.class;
                        break;
                    case "PathTraversalEvent":
                        eventClass = PathTraversalEvent.class;
                        break;
                    case "PersonArrivalEvent":
                        eventClass = PersonArrivalEvent.class;
                        break;
                    case "PersonDepartureEvent":
                        eventClass = PersonDepartureEvent.class;
                        break;
                    case "PersonEntersVehicleEvent":
                        eventClass = PersonEntersVehicleEvent.class;
                        break;
                    case "PersonLeavesVehicleEvent":
                        eventClass = PersonLeavesVehicleEvent.class;
                        break;
                    case "RefuelEvent":
                        eventClass = RefuelEvent.class;
                        break;
                    case "ReplanningEvent":
                        eventClass = ReplanningEvent.class;
                        break;
                    case "ReserveRideHailEvent":
                        eventClass = ReserveRideHailEvent.class;
                        break;
                    case "VehicleEntersTrafficEvent":
                        eventClass = VehicleEntersTrafficEvent.class;
                        break;
                    case "VehicleLeavesTrafficEvent":
                        eventClass = VehicleLeavesTrafficEvent.class;
                        break;
                    case "PersonCostEvent":
                        eventClass = PersonCostEvent.class;
                        break;
                    case "AgencyRevenueEvent":
                        eventClass = AgencyRevenueEvent.class;
                        break;
                    default:
                        DebugLib.stopSystemAndReportInconsistency("Logging class name: Unidentified event type class " + className);
                }
                //add the matched event class to the list of events to log
                if (eventClass != null)
                    eventsToLog.add(eventClass);
            }
        }
    }
}
