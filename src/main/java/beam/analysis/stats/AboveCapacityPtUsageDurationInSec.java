package beam.analysis.stats;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.BeamStats;
import beam.analysis.plots.IterationSummaryStats;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;

public class AboveCapacityPtUsageDurationInSec implements BeamStats, IterationSummaryStats {

    private double aboveCapacityPtUsageDurationInSec = 0;

    public AboveCapacityPtUsageDurationInSec() {

    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Integer numberOfPassengers = Integer.parseInt(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            Integer seatingCapacity = Integer.parseInt(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));
            Double departureTime = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME));
            Double arrivalTime = Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME));

            if (numberOfPassengers > seatingCapacity) {
                aboveCapacityPtUsageDurationInSec += arrivalTime - departureTime;
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        aboveCapacityPtUsageDurationInSec = 0;
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        Map<String, Double> result = new HashMap<>();
        result.put("AtCapacityPtUsageDurationInSec", aboveCapacityPtUsageDurationInSec);
        return result;
    }
}