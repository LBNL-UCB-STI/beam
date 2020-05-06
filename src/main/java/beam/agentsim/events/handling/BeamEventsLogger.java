package beam.agentsim.events.handling;

import beam.agentsim.events.*;
import beam.sim.config.BeamConfig;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.util.*;


/**
 * Logger class for BEAM events
 */
public class BeamEventsLogger {
    private final BeamConfig beamConfig;
    private final EventsManager eventsManager;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    private final List<BeamEventsWriterBase> writers = new ArrayList<>();
    private final Set<Class<?>> eventsToLog = new HashSet<>();
    private final List<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();

    public BeamEventsLogger(BeamConfig beamConfig, OutputDirectoryHierarchy outputDirectoryHierarchy, EventsManager eventsManager, String eventsToWrite) {
        this.beamConfig = beamConfig;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.eventsManager = eventsManager;
        overrideDefaultLoggerSetup(eventsToWrite);
    }

    public BeamEventsLogger(BeamConfig beamConfig, OutputDirectoryHierarchy outputDirectoryHierarchy, EventsManager eventsManager, String eventsToWrite,
                            boolean shouldInitialize, int iterationNumber) {
        this(beamConfig, outputDirectoryHierarchy, eventsManager, eventsToWrite);
        if (shouldInitialize) {
            setEventsFileFormats();
            createEventsWriters(iterationNumber);
        }
    }

    void iterationEnds() {
        for (BeamEventsWriterBase writer : writers) {
            writer.closeFile();
            eventsManager.removeHandler(writer);
        }
        writers.clear();
    }

    protected void createEventsWriters(int iterationNumber) {
        final int writeEventsInterval = beamConfig.beam().outputs().writeEventsInterval();
        final boolean writeThisIteration = (writeEventsInterval > 0) && (iterationNumber % writeEventsInterval == 0);
        if (writeThisIteration) {
            outputDirectoryHierarchy.createIterationDirectory(iterationNumber);
            String eventsFileBasePath = outputDirectoryHierarchy.getIterationFilename(iterationNumber, "events");
            for (BeamEventsFileFormats fmt : eventsFileFormatsArray) {
                BeamEventsWriterBase newWriter;
                newWriter = createEventWriterForClassAndFormat(eventsFileBasePath, null, fmt);
                writers.add(newWriter);
                eventsManager.addHandler(newWriter);
            }
        }
    }

    private BeamEventsWriterBase createEventWriterForClassAndFormat(String eventsFilePathBase,
                                                                    Class<?> theClass,
                                                                    BeamEventsFileFormats fmt) {
        final String path = eventsFilePathBase + "." + fmt.getSuffix();
        if (fmt == BeamEventsFileFormats.XML || fmt == BeamEventsFileFormats.XML_GZ) {
            return new BeamEventsWriterXML(path, this, theClass);
        } else if (fmt == BeamEventsFileFormats.CSV || fmt == BeamEventsFileFormats.CSV_GZ) {
            return new BeamEventsWriterCSV(path, this, theClass);
        } else if (fmt == BeamEventsFileFormats.PARQUET) {
            return new BeamEventsWriterParquet(path, this, theClass);
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
    protected void setEventsFileFormats() {
        eventsFileFormatsArray.clear();
        String eventsFileFormats = beamConfig.beam().outputs().events().fileOutputFormats();
        for (String format : eventsFileFormats.split(",")) {
            BeamEventsFileFormats.from(format).ifPresent(eventsFileFormatsArray::add);
        }
    }

    Set<String> getKeysToWrite(Event event, Map<String, String> eventAttributes) {
        return eventAttributes.keySet();
    }

    /**
     * Overrides the default logger setup
     */
    private void overrideDefaultLoggerSetup(String eventsToWrite) {
        Class<?> eventClass = null;
        // Generate the required event class reference based on the class name
        if (!eventsToWrite.isEmpty()) {
            for (String className : eventsToWrite.split(",")) {
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
                    case "ParkingEvent":
                        eventClass = ParkingEvent.class;
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
                    case "RefuelSessionEvent":
                        eventClass = RefuelSessionEvent.class;
                        break;
                    case "ChargingPlugOutEvent":
                        eventClass = ChargingPlugOutEvent.class;
                        break;
                    case "ChargingPlugInEvent":
                        eventClass = ChargingPlugInEvent.class;
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
                        DebugLib.stopSystemAndReportInconsistency(
                                "Logging class name: Unidentified event type class " + className);
                }
                //add the matched event class to the list of events to log
                eventsToLog.add(eventClass);
            }
        }
    }
}