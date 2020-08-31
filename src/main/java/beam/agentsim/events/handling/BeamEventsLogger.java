package beam.agentsim.events.handling;

import beam.agentsim.events.*;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;

import java.util.*;


/**
 * Logger class for BEAM events
 */
public class BeamEventsLogger {

    private final EventsManager eventsManager;
    private final MatsimServices matsimServices;
    private final BeamServices beamServices;
    private final List<BeamEventsWriterBase> writers = new ArrayList<>();
    private final Set<Class<?>> eventsToLog = new HashSet<>();
    private final List<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();

    public BeamEventsLogger(BeamServices beamServices, MatsimServices matsimServices, EventsManager eventsManager, String eventsToWrite) {
        this.beamServices = beamServices;
        this.matsimServices = matsimServices;
        this.eventsManager = eventsManager;
        overrideDefaultLoggerSetup(eventsToWrite);
    }

    public BeamEventsLogger(BeamServices beamServices, MatsimServices matsimServices, EventsManager eventsManager, String eventsToWrite, Boolean shouldInitialize) {
        this(beamServices, matsimServices, eventsManager, eventsToWrite);
        if (shouldInitialize) {
            setEventsFileFormats();
            createEventsWriters();
        }
    }

    void iterationEnds() {
        for (BeamEventsWriterBase writer : writers) {
            writer.closeFile();
            eventsManager.removeHandler(writer);
        }
        writers.clear();
    }

    protected void createEventsWriters() {
        int iterationNumber = matsimServices.getIterationNumber();

        final BeamConfig.Beam.Outputs outputsConfig = beamServices.beamConfig().beam().outputs();

        final int writeEventsInterval = outputsConfig.writeEventsInterval();
        final boolean writeAllEvents = (writeEventsInterval > 0) && (iterationNumber % writeEventsInterval == 0);

        final String extraEventsToWrite = outputsConfig.events().extraEventsToWrite().getOrElse(() -> "");
        final List<Class<?>> extraEventsClasses = getEventsClasses(extraEventsToWrite);
        final boolean writeExtraEvents = !extraEventsClasses.isEmpty();

        if (writeAllEvents || writeExtraEvents) {
            matsimServices.getControlerIO().createIterationDirectory(iterationNumber);

            for (BeamEventsFileFormats fmt : eventsFileFormatsArray) {

                if (writeAllEvents) {
                    final String allEventsFileBasePath =
                            matsimServices.getControlerIO().getIterationFilename(iterationNumber, "events");

                    final BeamEventsWriterBase writer =
                           createEventWriterForClassAndFormat(allEventsFileBasePath,
                                                              null,
                                                              fmt);
                    writers.add(writer);
                    eventsManager.addHandler(writer);
                }

                if (writeExtraEvents) {
                    for (Class<?> eventClass : extraEventsClasses) {
                        final String extraEventFileBasePath = matsimServices.getControlerIO()
                                .getIterationFilename(iterationNumber, eventClass.getSimpleName());

                        final BeamEventsWriterBase writer =
                                createEventWriterForClassAndFormat(extraEventFileBasePath,
                                                                   eventClass,
                                                                   fmt);
                        writers.add(writer);
                        eventsManager.addHandler(writer);
                    }
                }
            }
        }
    }

    private BeamEventsWriterBase createEventWriterForClassAndFormat(String eventsFilePathBase,
                                                                    Class<?> theClass,
                                                                    BeamEventsFileFormats fmt) {
        final String path = eventsFilePathBase + "." + fmt.getSuffix();
        if (fmt == BeamEventsFileFormats.XML || fmt == BeamEventsFileFormats.XML_GZ) {
            return new BeamEventsWriterXML(path, this, beamServices, theClass);
        } else if (fmt == BeamEventsFileFormats.CSV || fmt == BeamEventsFileFormats.CSV_GZ) {
            return new BeamEventsWriterCSV(path, this, beamServices, theClass);
        } else if (fmt == BeamEventsFileFormats.PARQUET) {
            return new BeamEventsWriterParquet(path, this, beamServices, theClass);
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
        String eventsFileFormats = beamServices.beamConfig().beam().outputs().events().fileOutputFormats();
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
        //add the matched event class to the list of events to log
        eventsToLog.addAll(getEventsClasses(eventsToWrite));
    }

    private List<Class<?>> getEventsClasses(String eventsToWrite) {
        final List<Class<?>> eventClasses = new LinkedList<>();
        // Generate the required event class reference based on the class name
        if (!eventsToWrite.isEmpty()) {
            for (String className : eventsToWrite.split(",")) {
                switch (className) {
                    case "ActivityStartEvent":
                        eventClasses.add(ActivityStartEvent.class);
                        break;
                    case "ActivityEndEvent":
                        eventClasses.add(ActivityEndEvent.class);
                        break;
                    case "LeavingParkingEvent":
                        eventClasses.add(LeavingParkingEvent.class);
                        break;
                    case "LinkEnterEvent":
                        eventClasses.add(LinkEnterEvent.class);
                        break;
                    case "LinkLeaveEvent":
                        eventClasses.add(LinkLeaveEvent.class);
                        break;
                    case "ModeChoiceEvent":
                        eventClasses.add(ModeChoiceEvent.class);
                        break;
                    case "ParkingEvent":
                        eventClasses.add(ParkingEvent.class);
                        break;
                    case "PathTraversalEvent":
                        eventClasses.add(PathTraversalEvent.class);
                        break;
                    case "PersonArrivalEvent":
                        eventClasses.add(PersonArrivalEvent.class);
                        break;
                    case "PersonDepartureEvent":
                        eventClasses.add(PersonDepartureEvent.class);
                        break;
                    case "PersonEntersVehicleEvent":
                        eventClasses.add(PersonEntersVehicleEvent.class);
                        break;
                    case "PersonLeavesVehicleEvent":
                        eventClasses.add(PersonLeavesVehicleEvent.class);
                        break;
                    case "RefuelSessionEvent":
                        eventClasses.add(RefuelSessionEvent.class);
                        break;
                    case "ChargingPlugOutEvent":
                        eventClasses.add(ChargingPlugOutEvent.class);
                        break;
                    case "ChargingPlugInEvent":
                        eventClasses.add(ChargingPlugInEvent.class);
                        break;
                    case "ReplanningEvent":
                        eventClasses.add(ReplanningEvent.class);
                        break;
                    case "ReserveRideHailEvent":
                        eventClasses.add(ReserveRideHailEvent.class);
                        break;
                    case "VehicleEntersTrafficEvent":
                        eventClasses.add(VehicleEntersTrafficEvent.class);
                        break;
                    case "VehicleLeavesTrafficEvent":
                        eventClasses.add(VehicleLeavesTrafficEvent.class);
                        break;
                    case "PersonCostEvent":
                        eventClasses.add(PersonCostEvent.class);
                        break;
                    case "AgencyRevenueEvent":
                        eventClasses.add(AgencyRevenueEvent.class);
                        break;
                    default:
                        DebugLib.stopSystemAndReportInconsistency(
                                "Logging class name: Unidentified event type class " + className);
                }
            }
        }
        return eventClasses;
    }
}
