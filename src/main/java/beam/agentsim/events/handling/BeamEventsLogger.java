package beam.agentsim.events.handling;

import beam.agentsim.events.PathTraversalEvent;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * BEAM
 */

public class BeamEventsLogger {
    private final EventsManager eventsManager;
    private ArrayList<BeamEventsWriterBase> writers = new ArrayList<>();

    HashMap<Class<?>, Integer> levels = new HashMap<>();
    private HashSet<Class<?>> allLoggableEvents = new HashSet<>(), eventsToLog=new HashSet<>();
    private BeamServices beamServices;
    private String eventsFileFormats;
    private ArrayList<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();
    private String logfilePath;

    public BeamEventsLogger(BeamServices beamServices, EventsManager eventsManager) {

        this.beamServices = beamServices;
        this.eventsManager = eventsManager;
        setEventsFileFormats();

        // Registry of BEAM events that can be logged by BeamEventLogger
        allLoggableEvents.add(PathTraversalEvent.class);

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

        if (this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel() > 0) {
            // All classes are logged by default
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

    private void setLoggingLevel(Class<?> eventType, int level) {
        levels.put(eventType, level);
    }

    public Integer getLoggingLevel(Event event) {
        if (levels.containsKey(event.getClass())) {
            return levels.get(event.getClass());
        } else {
            return this.beamServices.beamConfig().beam().outputs().defaultLoggingLevel();
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
