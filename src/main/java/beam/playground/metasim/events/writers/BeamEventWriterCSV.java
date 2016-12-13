package beam.playground.metasim.events.writers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.IntegerValueHashMap;
import beam.playground.metasim.events.EventLogger;

public class BeamEventWriterCSV extends BeamEventWriterXML {

	EventLogger eventLogger;
	IntegerValueHashMap<String> attributeToColumnIndexMapping;
	
	public BeamEventWriterCSV(String outfilename, EventLogger eventLogger) {
		super();
		this.out = IOUtils.getBufferedWriter(outfilename);
		this.eventLogger = eventLogger;
		
		reset(0);
		
		// move registerClass calls out of class, if need to make it more flexible
		for (Class cla : eventLogger.getControlEventTypesWithLogger()){
			registerClass(cla);
		}
		
		registerClass(ActivityEndEvent.class);
		registerClass(PersonDepartureEvent.class);
		registerClass(PersonArrivalEvent.class);
		registerClass(ActivityStartEvent.class);
		registerClass(TeleportationArrivalEvent.class);
		
		writeCSVHeaderAndCreateAttributeToIndexMapping();
		
	}

	@Override
	public void closeFile() {
		try {
			this.out.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void reset(final int iter) {
		attributeToColumnIndexMapping = new IntegerValueHashMap<>();
	}

	public void super_handleEvent(final Event event) {
		writeEvent(event);

		flushBuffer();
	}

	private void flushBuffer() {
		try {
			this.out.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeEvent(Event event) {
		String[] row = new String[attributeToColumnIndexMapping.getKeySet().size()];

		Map<String, String> attributes = event.getAttributes();
		for (String attribute : attributes.keySet()) {
			if (!attributeToColumnIndexMapping.containsKey(attribute)){
				DebugLib.stopSystemAndReportInconsistency("unkown attribute:" + attribute + ";class:" + event.getClass()); 
			}
			
			row[attributeToColumnIndexMapping.get(attribute)] = event.getAttributes().get(attribute);
		}

		try {

			for (int i = 0; i < row.length; i++) {
				String str = row[i];
				if (str != null) {
					this.out.append(str);
				}

				if (i < row.length - 1) {
					this.out.append(",");
				} else {
					this.out.append("\n");
				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private void writeCSVHeaderAndCreateAttributeToIndexMapping() {
		int counter = 0;
		for (String attribute : attributeToColumnIndexMapping.getKeySet()) {
			attributeToColumnIndexMapping.set(attribute, counter++);
			try {
				this.out.append(attribute);
				if (counter < attributeToColumnIndexMapping.getKeySet().size()) {
					this.out.append(",");
				} else {
					this.out.append("\n");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void registerClass(Class cla) {
		Field[] fields = cla.getFields();
		
		for (Field field:fields){
			if (field.getName().contains("ATTRIBUTE_")){
				try {
					//System.out.println(field.get(null));
					attributeToColumnIndexMapping.set(field.get(null).toString(), 0);
				} catch (IllegalArgumentException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

}
