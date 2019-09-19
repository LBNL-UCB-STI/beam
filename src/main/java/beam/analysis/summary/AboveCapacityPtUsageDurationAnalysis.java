package beam.analysis.summary;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.analysis.plots.GraphAnalysis;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;

public class AboveCapacityPtUsageDurationAnalysis implements GraphAnalysis, IterationSummaryAnalysis {

    private double aboveCapacityPtUsageDuration = 0.0;

    public AboveCapacityPtUsageDurationAnalysis() {

    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent)event;
            Integer numberOfPassengers = pte.numberOfPassengers();
            Integer seatingCapacity = pte.seatingCapacity();
            int departureTime = pte.departureTime();
            int arrivalTime = pte.arrivalTime();

            if (numberOfPassengers > seatingCapacity) {
                aboveCapacityPtUsageDuration += arrivalTime - departureTime;
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        aboveCapacityPtUsageDuration = 0.0;
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> result = new HashMap<>();
        result.put("agentHoursOnCrowdedTransit", aboveCapacityPtUsageDuration /3600.0);
        return result;
    }
}
