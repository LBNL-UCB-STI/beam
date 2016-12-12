package beam.events.writers;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import beam.EVGlobalData;
import beam.events.EventLogger;
import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.IntegerValueHashMap;

public class EVEventWriterCSV extends EVEventWriterXML {

	IntegerValueHashMap<String> attributeToColumnIndexMapping;

	public EVEventWriterCSV(String outfilename) {
		super();
		this.out = IOUtils.getBufferedWriter(outfilename);
		
		reset(0);
		
		// move registerClass calls out of class, if need to make it more flexible
		EventLogger eventLogger = EVGlobalData.data.eventLogger;
		for (Class cla:EVGlobalData.data.eventLogger.getControlEventTypesWithLogger()){
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
			if (EVGlobalData.data.IS_DEBUG_MODE) {
				// TODO: this conditional statement can be removed, if this
				// flush statement does not deteriorate performance
				this.out.flush();
			}

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
