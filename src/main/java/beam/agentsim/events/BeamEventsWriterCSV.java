package beam.agentsim.events;

import beam.sim.BeamServices;
import beam.utils.DebugLib;
import beam.utils.IntegerValueHashMap;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.utils.io.UncheckedIOException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * BEAM
 */
public class BeamEventsWriterCSV extends BeamEventsWriterBase{
    IntegerValueHashMap<String> attributeToColumnIndexMapping = new IntegerValueHashMap<>();

    public BeamEventsWriterCSV(String outfilename, BeamEventsLogger eventLogger, MatsimServices services, BeamServices beamServices, Class<?> eventTypeToLog) {
        super(outfilename, eventLogger, services, beamServices, eventTypeToLog);

        if(eventTypeToLog==null){
            for(Class<?> clazz : eventLogger.getAllEventsToLog()){
                registerClass(clazz);
            }
        }else{
            registerClass(eventTypeToLog);
        }
        writeHeaders();
    }

    @Override
    public void writeHeaders(){
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
        String[] row = new String[attributeToColumnIndexMapping.getKeySet().size()];

        Map<String, String> attributes = event.getAttributes();
        for (String attribute : attributes.keySet()) {
            if (!attributeToColumnIndexMapping.containsKey(attribute)){
                if(this.eventTypeToLog == null || !attribute.equals(Event.ATTRIBUTE_TYPE)){
                    DebugLib.stopSystemAndReportInconsistency("unkown attribute:" + attribute + ";class:" + event.getClass());
                }
            }
            if(this.eventTypeToLog == null || !attribute.equals(Event.ATTRIBUTE_TYPE)){
                row[attributeToColumnIndexMapping.get(attribute)] = event.getAttributes().get(attribute);
            }
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
            this.out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void registerClass(Class cla) {
        Field[] fields = cla.getFields();

        for (Field field:fields){
            if (field.getName().contains("ATTRIBUTE_") && (this.eventTypeToLog == null || !field.getName().equals("ATTRIBUTE_TYPE"))){
                try {
                    attributeToColumnIndexMapping.set(field.get(null).toString(), 0);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
