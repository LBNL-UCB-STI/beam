package beam.playground.jdeqsim.akkaeventsampling;

import org.matsim.api.core.v01.events.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class Dictionary {
    public static List<Event> eventList = Collections.synchronizedList(new ArrayList<>());


    private String toLexicographicSortedString(Event event) {
        List<String> strings = new ArrayList<String>();
        for (Map.Entry<String, String> e : event.getAttributes().entrySet()) {
            StringBuilder tmp = new StringBuilder();
            tmp.append(e.getKey());
            tmp.append("=");
            tmp.append(e.getValue());
            strings.add(tmp.toString());
        }
        Collections.sort(strings);
        StringBuilder eventStr = new StringBuilder();
        for (String str : strings) {
            eventStr.append("|");
            eventStr.append(str);
        }
        return eventStr.toString();
    }
}