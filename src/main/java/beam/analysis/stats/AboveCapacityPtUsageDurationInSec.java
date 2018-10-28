package beam.analysis.stats;

import beam.agentsim.events.PathTraversalEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.BasicEventHandler;

import java.util.HashMap;
import java.util.Map;

public class AboveCapacityPtUsageDurationInSec extends CSVStats implements BasicEventHandler {

    double aboveCapacityPtUsageDurationInSec=0;

    public AboveCapacityPtUsageDurationInSec(EventsManager eventsManager) {
        super(eventsManager);
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        HashMap<String,Double> result=new HashMap<>();
        result.put("AtCapacityPtUsageDurationInSec",aboveCapacityPtUsageDurationInSec);
        return result;
    }

    @Override
    public void handleEvent(Event event){
        if (event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)){
            Integer numberOfPassengers = Integer.parseInt(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            Integer seatingCapacity = Integer.parseInt(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));
            Double departureTime = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME));
            Double arrivalTime = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME));

            if (numberOfPassengers > seatingCapacity){
                aboveCapacityPtUsageDurationInSec+=arrivalTime-departureTime;
            }
        }
    }

    @Override
    public void reset(int iteration) {
        aboveCapacityPtUsageDurationInSec=0;
    }

}