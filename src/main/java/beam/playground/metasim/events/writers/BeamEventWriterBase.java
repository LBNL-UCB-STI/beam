package beam.playground.metasim.events.writers;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import com.google.inject.Inject;

import beam.playground.metasim.events.BeamEventLogger;
import beam.playground.metasim.services.BeamServices;

public abstract class BeamEventWriterBase implements EventWriter, BasicEventHandler {
	protected BufferedWriter out;
	protected BeamEventLogger beamEventLogger; 
	protected MatsimServices matsimServices; 
	protected BeamServices beamServices;
	protected Class<?> eventTypeToLog; 

	public BeamEventWriterBase(String outfilename, BeamEventLogger beamEventLogger, MatsimServices matsimServices, BeamServices beamServices, Class<?> eventTypeToLog) {
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
