package beam.utils.debug;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.HashSet;
import java.util.Map;

// see issue 272
public class DetectDriveTransitRemainingEnRoute implements BasicEventHandler {

    private final HashSet<String> personIdsOnDriveTransit = new HashSet<>();

    public static void main(String[] args) {
        EventsManager events = EventsUtils.createEventsManager();
        DetectDriveTransitRemainingEnRoute detectDriveTransitRemainingEnRoute = new DetectDriveTransitRemainingEnRoute();
        events.addHandler(detectDriveTransitRemainingEnRoute);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile("C:\\Users\\rwaraich\\IdeaProjects\\beam-feb-2018-1\\beam\\output\\application-sfbay\\base__2018-05-09_11-56-49\\ITERS\\it.0\\0.events.xml.gz");

// print agents which stay on route at the end of the simulation
        detectDriveTransitRemainingEnRoute.printAgents();
    }

    private void printAgents() {
        for (String personId : personIdsOnDriveTransit) {
            System.out.println(personId);
        }
    }


    @Override
    public void handleEvent(Event event) {
        Map<String, String> eventAttributes = event.getAttributes();
        if (event.getEventType().equalsIgnoreCase("departure")) {
            if (eventAttributes.get("legMode").equalsIgnoreCase("drive_transit")) {
                personIdsOnDriveTransit.add(eventAttributes.get("person"));
            }
        } else if (event.getEventType().equalsIgnoreCase("arrival")) {
            if (eventAttributes.get("legMode").equalsIgnoreCase("drive_transit")) {
                personIdsOnDriveTransit.remove(eventAttributes.get("person"));
            }
        }
    }
}


/*
2096-0
4865-4
4865-2
3849-3
3849-4
2560-1
3849-0
2167-1
5749-0
3742-1
3742-0
2785-1
2358-1
2358-3
5857-3
8159-2
8159-0
3031-1
3031-0
 */