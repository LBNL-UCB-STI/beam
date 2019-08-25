package beam.agentsim.events.handling;

import beam.sim.BeamServices;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedWriter;

/**
 * BEAM
 */
public class BeamEventsWriterBase implements EventWriter, BasicEventHandler {
    protected final BufferedWriter outWriter;
    protected final BeamEventsLogger beamEventLogger;
    protected final BeamServices beamServices;
    protected final Class<?> eventTypeToLog;


    public BeamEventsWriterBase(String outfilename, BeamEventsLogger beamEventLogger, BeamServices beamServices, Class<?> eventTypeToLog) {
        this.beamEventLogger = beamEventLogger;
        this.beamServices = beamServices;
        this.outWriter = IOUtils.getBufferedWriter(outfilename);
        this.eventTypeToLog = eventTypeToLog;
    }

    public BeamEventsWriterBase(BeamEventsLogger beamEventLogger, BeamServices beamServices, Class<?> eventTypeToLog) {
        this.beamEventLogger = beamEventLogger;
        this.beamServices = beamServices;
        this.eventTypeToLog = eventTypeToLog;
        this.outWriter = null;
    }

    @Override
    public void handleEvent(final Event event) {
        if ((eventTypeToLog == null && beamEventLogger.shouldLogThisEventType(event.getClass())) || eventTypeToLog == event.getClass()) {
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

    public void writeHeaders() {
    }

}
