package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.BeamAnalysis;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.events.Event;

import java.util.HashMap;
import java.util.Map;

public class TollRevenueAnalysis implements BeamAnalysis, IterationSummaryAnalysis {

    public static final String ATTRIBUTE_TOLL_REVENUE = "tollRevenue";
    private double tollRevenue = 0.0;

    @Override
    public void processStats(Event event) {
        if (event.getEventType().equals(PathTraversalEvent.EVENT_TYPE)) {
            tollRevenue += Double.parseDouble(event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_TOLL_PAID));
        }
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        HashMap<String, Double> result = new HashMap<>();
        result.put(ATTRIBUTE_TOLL_REVENUE, tollRevenue);
        return result;
    }

    @Override
    public void resetStats() {
        tollRevenue = 0.0;
    }

}
