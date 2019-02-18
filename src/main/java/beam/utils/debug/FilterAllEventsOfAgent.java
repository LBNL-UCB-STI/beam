package beam.utils.debug;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.LinkedList;
import java.util.Map;

public class FilterAllEventsOfAgent implements BasicEventHandler {

    final LinkedList<Event> filteredEvents = new LinkedList<>();
    private final String personId;

    public FilterAllEventsOfAgent(String personId) {
        this.personId = personId;
    }

    public static void main(String[] args) {
        EventsManager events = EventsUtils.createEventsManager();
        FilterAllEventsOfAgent filterAllEventsOfAgent = new FilterAllEventsOfAgent("4865-4");
        events.addHandler(filterAllEventsOfAgent);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile("C:\\Users\\rwaraich\\IdeaProjects\\beam-feb-2018-1\\beam\\output\\application-sfbay\\base__2018-05-09_11-56-49\\ITERS\\it.0\\0.events.xml.gz");

// print agents which stay on route at the end of the simulation
        filterAllEventsOfAgent.printEvents();
    }

    public void printEvents() {
        for (Event event : filteredEvents) {
            System.out.println(event);
        }
    }


    @Override
    public void handleEvent(Event event) {
        Map<String, String> eventAttributes = event.getAttributes();
        if (eventAttributes.containsKey("person") && eventAttributes.get("person").equalsIgnoreCase(personId)) {
            filteredEvents.add(event);
        }
    }
}