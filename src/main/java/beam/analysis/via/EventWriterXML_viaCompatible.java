package beam.analysis.via;


import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.algorithms.EventWriter;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

// for some reason via is expecting wait2link event, instead of vehicle enters traffic event (dec. 2017)
// perhaps also has to do with the fact, that we are not using most uptodate matsim version
// this class can be replaced by default EventWriterXML, as this issue gets resolved.

public class EventWriterXML_viaCompatible implements EventWriter, BasicEventHandler {
    private static final String TNC = "ride";
    private static final String BUS = "SF";
    private static final String CAR = "car";
    private final BufferedWriter out;
    private boolean eventsForFullVersionOfVia;
    HashMap<String, HashSet<String>> filterPeopleForViaDemo = new HashMap<>();
    HashMap<String, Integer> maxPeopleForViaDemo = new HashMap<>();

    public EventWriterXML_viaCompatible(final String outFileName, boolean eventsForFullVersionOfVia) {
        this.out = IOUtils.getBufferedWriter(outFileName);
        this.eventsForFullVersionOfVia = eventsForFullVersionOfVia;

        filterPeopleForViaDemo.put(CAR, new HashSet<>());
        filterPeopleForViaDemo.put(BUS, new HashSet<>());
        filterPeopleForViaDemo.put(TNC, new HashSet<>());

        maxPeopleForViaDemo.put(CAR, 420);
        maxPeopleForViaDemo.put(BUS, 50);
        maxPeopleForViaDemo.put(TNC, 30);

        try {
            this.out.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void closeFile() {
        try {
            this.out.write("</events>");
            // I added a "\n" to make it look nicer on the console.  Can't say if this may have unintended side
            // effects anywhere else.  kai, oct'12
            // fails signalsystems test (and presumably other tests in contrib/playground) since they compare
            // checksums of event files.  Removed that change again.  kai, oct'12
            this.out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Deprecated
    public void init(final String outfilename) {
        throw new RuntimeException("Please create a new instance.");
    }

    @Override
    public void reset(final int iter) {
    }

    private boolean addPersonToEventsFile(String person) {

        if (eventsForFullVersionOfVia){
            return true;
        }

        String personLabel;

        if (person.contains(BUS)) {
            personLabel = BUS;
        } else if (person.contains(TNC)) {
            personLabel = TNC;
        } else {
            personLabel = CAR;
        }

        if (filterPeopleForViaDemo.get(personLabel).size() < maxPeopleForViaDemo.get(personLabel) || filterPeopleForViaDemo.get(personLabel).contains(person)) {
            filterPeopleForViaDemo.get(personLabel).add(person);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void handleEvent(final Event event) {

        // select 500 agents for sf-light demo in via
        //if (outFileName.contains("sf-light")){
        Map<String, String> eventAttributes = event.getAttributes();
        String person = eventAttributes.get("person");
        String vehicle = eventAttributes.get("vehicle");

        if (person != null) {
            if (!addPersonToEventsFile(person)) return;
        } else {
            if (!addPersonToEventsFile(vehicle)) return;
        }
        //}

        try {
            this.out.append("\t<event ");

            if (eventAttributes.get("type").equalsIgnoreCase("vehicle enters traffic")) {
                eventAttributes.put("type", "wait2link");
            }


            for (Map.Entry<String, String> entry : eventAttributes.entrySet()) {
                this.out.append(entry.getKey());
                this.out.append("=\"");
                this.out.append(encodeAttributeValue(entry.getValue()));
                this.out.append("\" ");
            }
            this.out.append(" />\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // the following method was taken from MatsimXmlWriter in order to correctly encode attributes, but
    // to forego the overhead of using the full MatsimXmlWriter.

    /**
     * Encodes the given string in such a way that it no longer contains
     * characters that have a special meaning in xml.
     *
     * @param attributeValue
     * @return String with some characters replaced by their xml-encoding.
     * @see <a href="http://www.w3.org/International/questions/qa-escapes#use">http://www.w3.org/International/questions/qa-escapes#use</a>
     */
    private String encodeAttributeValue(final String attributeValue) {
        if (attributeValue == null) {
            return null;
        }
        int len = attributeValue.length();
        boolean encode = false;
        for (int pos = 0; pos < len; pos++) {
            char ch = attributeValue.charAt(pos);
            if (ch == '<') {
                encode = true;
                break;
            } else if (ch == '>') {
                encode = true;
                break;
            } else if (ch == '\"') {
                encode = true;
                break;
            } else if (ch == '&') {
                encode = true;
                break;
            }
        }
        if (encode) {
            StringBuilder bf = new StringBuilder();
            for (int pos = 0; pos < len; pos++) {
                char ch = attributeValue.charAt(pos);
                if (ch == '<') {
                    bf.append("&lt;");
                } else if (ch == '>') {
                    bf.append("&gt;");
                } else if (ch == '\"') {
                    bf.append("&quot;");
                } else if (ch == '&') {
                    bf.append("&amp;");
                } else {
                    bf.append(ch);
                }
            }

            return bf.toString();
        }
        return attributeValue;

    }

}

