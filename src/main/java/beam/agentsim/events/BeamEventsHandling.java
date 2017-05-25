package beam.agentsim.events;

import beam.agentsim.config.BeamConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.events.algorithms.EventWriter;
import beam.sim.BeamServices;

import java.util.LinkedList;
import java.util.List;

/**
 * BEAM
 */
@Singleton
public final class BeamEventsHandling implements EventsHandling, BeforeMobsimListener, AfterMobsimListener, IterationEndsListener, ShutdownListener {
    private final EventsManager eventsManager;
    private List<EventWriter> eventWriters = new LinkedList<>();
    private BeamServices services;
    private MatsimServices matsimServices;
    private BeamEventsLogger eventsLogger;
    private BeamConfig beamConfig;

    @Inject
    BeamEventsHandling(EventsManager eventsManager, BeamServices beamServices) {
        this.eventsManager = eventsManager;
        this.matsimServices = beamServices.matsimServices();
        this.services = beamServices;
    }

    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        this.beamConfig = this.services.beamConfig();
        if(this.eventsLogger == null) this.eventsLogger = new BeamEventsLogger(services, matsimServices);
        eventsManager.resetHandlers(event.getIteration());
        boolean writeThisIteration = (beamConfig.beam().outputs().writeEventsInterval() > 0) && (event.getIteration() % beamConfig.beam().outputs().writeEventsInterval() == 0);
        if(writeThisIteration){
            if(beamConfig.beam().outputs().explodeEventsIntoFiles()){
                for(Class<?>eventTypeToLog : eventsLogger.getAllEventsToLog()){
                    createWriters("events."+eventTypeToLog.getSimpleName(),eventTypeToLog, event.getIteration());
                }
            }else{
                createWriters("events",null, event.getIteration());
            }
        }
        for (EventWriter writer : this.eventWriters) {
            eventsManager.addHandler(writer);
        }
        // init for event processing of new iteration
        eventsManager.initProcessing();
    }

    private void createWriters(String fileNameBase, Class<?> eventTypeToLog, Integer iterationNum) {
        String xmlEventFileName = fileNameBase;
        Boolean createXMLWriter = false;
        if (eventsLogger.logEventsInFormat(BeamEventsFileFormats.xmlgz)){
            xmlEventFileName += ".xml.gz";
            createXMLWriter = true;
        }else if (eventsLogger.logEventsInFormat(BeamEventsFileFormats.xml)) {
            xmlEventFileName += ".xml";
            createXMLWriter = true;
        }
        if(createXMLWriter)this.eventWriters.add(new BeamEventsWriterXML(matsimServices.getControlerIO().getIterationFilename(iterationNum, xmlEventFileName),eventsLogger, matsimServices, services, eventTypeToLog));

        String csvEventFileName = fileNameBase;
        Boolean createCSVWriter = false;
        if (eventsLogger.logEventsInFormat(BeamEventsFileFormats.csvgz)) {
            csvEventFileName += ".csv.gz";
            createCSVWriter = true;
        }else if (eventsLogger.logEventsInFormat(BeamEventsFileFormats.csv)) {
            csvEventFileName += ".csv";
            createCSVWriter = true;
        }
        if(createCSVWriter)this.eventWriters.add(new BeamEventsWriterCSV(matsimServices.getControlerIO().getIterationFilename(iterationNum, csvEventFileName),eventsLogger, matsimServices, services, eventTypeToLog));
    }

    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {

    /*
     * cdobler, nov'10 Moved this code here from
     * Controler.CoreControlerListener.notifyAfterMobsim(...). It ensures,
     * that if a ParallelEventsManager is used, all events are processed
     * before the AfterMobSimListeners are informed. Otherwise e.g. usage of
     * ParallelEventsManager and RoadPricing was not possible - MATSim
     * crashed. After this command, the ParallelEventsManager behaves like
     * the non-parallel implementation, therefore the main thread will have
     * to wait until a created event has been handled.
     *
     * This means, this thing prevents _two_ different bad things from
     * happening: 1.) Road pricing (for example) from starting to calculate
     * road prices while Mobsim-Events are still coming in (and crashing)
     * 2.) Later things which happen in the Controler (e.g. Scoring) from
     * starting to score while (for example) road pricing events are still
     * coming in (and crashing). michaz (talking to cdobler), jun'13
     */
        eventsManager.finishProcessing();

    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
    /*
     * Events that are produced after the Mobsim has ended, e.g. by the
     * RoadProcing module, should also be written to the events file.
     */
        for (EventWriter writer : this.eventWriters) {
            writer.closeFile();
            this.eventsManager.removeHandler(writer);
        }
        this.eventWriters.clear();
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        for (EventWriter writer : this.eventWriters) {
            writer.closeFile();
        }
    }

}
