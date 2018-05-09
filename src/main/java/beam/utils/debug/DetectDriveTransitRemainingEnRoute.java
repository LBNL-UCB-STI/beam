package beam.utils.debug;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.HashSet;

// see issue 272
public class DetectDriveTransitRemainingEnRoute implements BasicEventHandler {

    HashSet<String> personIdsOnDriveTransit;

    public static void main(String[] args) {
        EventsManager events = EventsUtils.createEventsManager();
        DetectDriveTransitRemainingEnRoute detectDriveTransitRemainingEnRoute=new DetectDriveTransitRemainingEnRoute();
        events.addHandler(detectDriveTransitRemainingEnRoute);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile("C:\\Users\\NRO_M4700_SSD_02\\IdeaProjects\\beam-may-2nd\\beam-may-2nd\\output\\beamville\\beamville__2018-05-09_14-35-32\\ITERS\\it.0\\0.events.xml");
        detectDriveTransitRemainingEnRoute.printAgents();
    }


    public void printAgents(){

    }


    @Override
    public void handleEvent(Event event) {
        System.out.println();
    }
}
