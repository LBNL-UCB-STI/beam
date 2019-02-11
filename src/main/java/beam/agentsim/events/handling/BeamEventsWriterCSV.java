package beam.agentsim.events.handling;

import beam.sim.BeamServices;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.utils.io.UncheckedIOException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BEAM
 */
public class BeamEventsWriterCSV extends BeamEventsWriterBase {

    private final LinkedHashMap<String, Integer> attributeToColumnIndexMapping = new LinkedHashMap<>();

    BeamEventsWriterCSV(String outfilename, BeamEventsLogger eventLogger, BeamServices beamServices, Class<?> eventTypeToLog) {
        super(outfilename, eventLogger, beamServices, eventTypeToLog);

        if (eventTypeToLog == null) {
            for (Class<?> clazz : eventLogger.getAllEventsToLog()) {
                registerClass(clazz);
            }
        } else {
            registerClass(eventTypeToLog);
        }
        writeHeaders();
    }

    @Override
    public void writeHeaders() {
        int counter = 0;
        for (String attribute : attributeToColumnIndexMapping.keySet()) {
            attributeToColumnIndexMapping.put(attribute, counter++);
            try {
                this.out.append(attribute);
                if (counter < attributeToColumnIndexMapping.keySet().size()) {
                    this.out.append(",");
                } else {
                    this.out.append("\n");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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
    }

    @Override
    protected void writeEvent(Event event) {
//        if (beamEventLogger.getLoggingLevel(event) == OFF) return;
        String[] row = new String[attributeToColumnIndexMapping.keySet().size()];
        Map<String, String> eventAttributes = event.getAttributes();
        Set<String> attributeKeys = this.beamEventLogger.getKeysToWrite(event, eventAttributes);
        for (String attribute : attributeKeys) {
            if (!attributeToColumnIndexMapping.containsKey(attribute)) {
                if (this.eventTypeToLog == null || !attribute.equals(Event.ATTRIBUTE_TYPE)) {
                    DebugLib.stopSystemAndReportInconsistency("unkown attribute:" + attribute + ";class:" + event.getClass());
                }
            }
            if (this.eventTypeToLog == null || !attribute.equals(Event.ATTRIBUTE_TYPE)) {
                row[attributeToColumnIndexMapping.get(attribute)] = eventAttributes.get(attribute);
            }
        }
        try {
            for (int i = 0; i < row.length; i++) {
                String str = row[i];
                if (str != null) {
                    if (str.contains(",")) {
                        this.out.append('"');
                        this.out.append(str);
                        this.out.append('"');
                    } else {
                        this.out.append(str);
                    }
                }
                if (i < row.length - 1) {
                    this.out.append(",");
                } else {
                    this.out.append("\n");
                }
            }
            this.out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void registerClass(Class cla) {
        Field[] fields = cla.getFields();
        for (Field field : fields) {
            if ((field.getName().startsWith("ATTRIBUTE_") && (eventTypeToLog == null || !field.getName().startsWith("ATTRIBUTE_TYPE"))) ||
                    (field.getName().startsWith("VERBOSE_") && (eventTypeToLog == null || !field.getName().startsWith("VERBOSE_")))
                    ) {
                try {
                    attributeToColumnIndexMapping.put(field.get(null).toString(), 0);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
