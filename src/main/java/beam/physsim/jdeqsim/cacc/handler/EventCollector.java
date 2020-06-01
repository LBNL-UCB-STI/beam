package beam.physsim.jdeqsim.cacc.handler;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.ArrayList;
import java.util.List;

public class EventCollector implements BasicEventHandler{

    int counter = 0;

    final List<Event> events;

    @Override
    public void reset(int iteration) {
        counter = 0;
    }

    public EventCollector(){
        events = new ArrayList<>();
    }

    @Override
    public void handleEvent(Event event) {
        //System.out.println(event + " -> " + counter++);
        events.add(event);
    }

    public List<Event> getEvents() {
        return events;
    }

    public void logEvents(){
        System.out.println("Logging events for " + getClass().getName());
        for(Event event : events){
            System.out.println(event);
        }
    }
}
