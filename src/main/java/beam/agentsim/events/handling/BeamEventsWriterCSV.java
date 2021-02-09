package beam.agentsim.events.handling;

import beam.agentsim.events.ScalaEvent;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.utils.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BEAM
 */
public class BeamEventsWriterCSV extends BeamEventsWriterBase {
    private final Logger log = LoggerFactory.getLogger(BeamEventsWriterCSV.class);
    private final LinkedHashMap<String, Integer> attributeToColumnIndexMapping = new LinkedHashMap<>();

    public BeamEventsWriterCSV(final String outfilename,
                               final BeamEventsLoggingSettings settings,
                               final BeamServices beamServices,
                               final Class<?> eventTypeToLog) {
        super(outfilename, settings, beamServices, eventTypeToLog);

        if (eventTypeToLog == null) {
            for (Class<?> clazz : settings.getAllEventsToLog()) {
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
                this.outWriter.append(attribute);
                if (counter < attributeToColumnIndexMapping.keySet().size()) {
                    this.outWriter.append(",");
                } else {
                    this.outWriter.append("\n");
                }
            } catch (IOException e) {
                log.error("exception occurred due to ", e);
            }
        }
    }

    @Override
    public void reset(final int iter) {
    }

    @Override
    public void closeFile() {
        try {
            this.outWriter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void writeEvent(Event event) {
//        if (beamEventLogger.getLoggingLevel(event) == OFF) return;
        String[] row = new String[attributeToColumnIndexMapping.keySet().size()];
        Map<String, String> eventAttributes = event.getAttributes();
        Set<String> attributeKeys = this.settings.getKeysToWrite(event, eventAttributes);
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
                        this.outWriter.append('"');
                        this.outWriter.append(str);
                        this.outWriter.append('"');
                    } else {
                        this.outWriter.append(str);
                    }
                }
                if (i < row.length - 1) {
                    this.outWriter.append(",");
                } else {
                    this.outWriter.append("\n");
                }
            }
            this.outWriter.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void registerClass(Class<?> cla) {
        // ScalaEvent classes are from scala, so we have to have special treatment for them
        // scala's val and var are not actual fields, but methods (getters and setters)
        if (ScalaEvent.class.isAssignableFrom(cla)) {
            for(Method method : cla.getDeclaredMethods()) {
                String name = method.getName();
                if ((name.startsWith("ATTRIBUTE_") && (eventTypeToLog == null || !name.startsWith("ATTRIBUTE_TYPE"))) ||
                        (name.startsWith("VERBOSE_") && (eventTypeToLog == null || !name.startsWith("VERBOSE_")))
                ) {
                    try {
                        // Call static method
                        String value = (String)method.invoke(null);
                        attributeToColumnIndexMapping.put(value, 0);
                    } catch (Exception e) {
                        log.error("exception occurred due to ", e);
                    }
                }
            }
        }
        Field[] fields = cla.getFields();
        for (Field field : fields) {
            if ((field.getName().startsWith("ATTRIBUTE_") && (eventTypeToLog == null || !field.getName().startsWith("ATTRIBUTE_TYPE"))) ||
                    (field.getName().startsWith("VERBOSE_") && (eventTypeToLog == null || !field.getName().startsWith("VERBOSE_")))
            ) {
                try {
                    attributeToColumnIndexMapping.put(field.get(null).toString(), 0);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    log.error("exception occurred due to ", e);
                }
            }
        }
    }
}
