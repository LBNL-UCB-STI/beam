package beam.agentsim.events;

import beam.agentsim.sim.AgentsimServices;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedWriter;

/**
 * BEAM
 */
public class BeamEventsWriterBase implements EventWriter, BasicEventHandler {
    protected BufferedWriter out;
    protected BeamEventsLogger beamEventLogger;
    protected MatsimServices matsimServices;
    protected AgentsimServices beamServices;
    protected Class<?> eventTypeToLog;

    public BeamEventsWriterBase(String outfilename, BeamEventsLogger beamEventLogger, MatsimServices matsimServices, AgentsimServices beamServices, Class<?> eventTypeToLog) {
        this.beamEventLogger = beamEventLogger;
        this.matsimServices = matsimServices;
        this.beamServices = beamServices;
        this.out = IOUtils.getBufferedWriter(outfilename);
        this.eventTypeToLog = eventTypeToLog;
    }

    public void writeHeaders(){
    }

    @Override
    public void handleEvent(final Event event) {
        if((this.eventTypeToLog != null && this.eventTypeToLog == event.getClass()) ||
                (this.eventTypeToLog == null && beamEventLogger.logEventsOfClass(event.getClass())) ){
            writeEvent(event);
        }
    }


    @Override
    public void closeFile() {
    }

    @Override
    public void reset(final int iter) {
    }

    protected void writeEvent(final Event event) {
    }

}
