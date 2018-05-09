package beam.utils.debug;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.HashSet;

// see issue 272
public class DetectDriveTransitRemainingEnRoute implements BasicEventHandler {

    HashSet<String> personIdsOnDriveTransit=new HashSet<>();

    public static void main(String[] args) {
        EventsManager events = EventsUtils.createEventsManager();
        DetectDriveTransitRemainingEnRoute detectDriveTransitRemainingEnRoute=new DetectDriveTransitRemainingEnRoute();
        events.addHandler(detectDriveTransitRemainingEnRoute);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile("C:\\Users\\rwaraich\\IdeaProjects\\beam-feb-2018-1\\beam\\output\\application-sfbay\\base__2018-05-09_11-56-49\\ITERS\\it.0\\0.events.xml.gz");

// print agents which stay on route at the end of the simulation
        detectDriveTransitRemainingEnRoute.printAgents();
    }

    public void printAgents(){
        for (String personId:personIdsOnDriveTransit){
            System.out.println(personId);
        }
    }


    @Override
    public void handleEvent(Event event) {
        if (event.getEventType().equalsIgnoreCase("departure")){
            if (event.getAttributes().get("legMode").equalsIgnoreCase("drive_transit")) {
                personIdsOnDriveTransit.add(event.getAttributes().get("person").toString());
            }
        } else if (event.getEventType().equalsIgnoreCase("arrival")){
            if (event.getAttributes().get("legMode").equalsIgnoreCase("drive_transit")) {
                personIdsOnDriveTransit.remove(event.getAttributes().get("person").toString());
            }
        }
    }
}
