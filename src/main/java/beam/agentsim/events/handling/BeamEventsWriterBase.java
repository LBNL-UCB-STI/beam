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
    protected final BeamEventsLoggingSettings settings;
    protected final BeamServices beamServices;
    protected final Class<?> eventTypeToLog;

    public BeamEventsWriterBase(final BufferedWriter outWriter,
                                final BeamEventsLoggingSettings settings,
                                final BeamServices beamServices,
                                final Class<?> eventTypeToLog) {
        this.outWriter = outWriter;
        this.settings = settings;
        this.beamServices = beamServices;
        this.eventTypeToLog = eventTypeToLog;
    }

    public BeamEventsWriterBase(final String outfilename,
                                final BeamEventsLoggingSettings settings,
                                final BeamServices beamServices,
                                final Class<?> eventTypeToLog) {
        this(IOUtils.getBufferedWriter(outfilename), settings, beamServices, eventTypeToLog);
    }

    public BeamEventsWriterBase(final BeamEventsLoggingSettings settings,
                                final BeamServices beamServices,
                                final Class<?> eventTypeToLog) {
        this((BufferedWriter) null, settings, beamServices, eventTypeToLog);
    }

    @Override
    public void handleEvent(final Event event) {
        if ((eventTypeToLog == null && settings.shouldLogThisEventType(event.getClass()))
                || eventTypeToLog == event.getClass()) {
            writeEvent(event);
        }
    }


    @Override
    public void closeFile() {
    }

    @Override
    public void reset(final int iter) {
    }

    public void writeEvent(final Event event) {
    }

    public void writeHeaders() {
    }

}
