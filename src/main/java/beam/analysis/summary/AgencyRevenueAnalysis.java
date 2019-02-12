package beam.analysis.summary;

import beam.agentsim.events.AgencyRevenueEvent;
import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.events.Event;

import java.util.HashMap;
import java.util.Map;

public class AgencyRevenueAnalysis implements IterationSummaryAnalysis {
    private final Map<String,Double> agencyRevenue = new HashMap<>();

    @Override
    public void processStats(Event event) {
        if (event instanceof AgencyRevenueEvent || event.getEventType().equalsIgnoreCase(AgencyRevenueEvent.EVENT_TYPE)){
            Map<String, String> attributes = event.getAttributes();
            String agencyId = attributes.get(AgencyRevenueEvent.ATTRIBUTE_AGENCY_ID);
            Double revenue = Double.parseDouble(attributes.get(AgencyRevenueEvent.ATTRIBUTE_REVENUE));

            if (!agencyRevenue.containsKey(agencyId)){
                agencyRevenue.put(agencyId,0.0);
            }

            revenue+=agencyRevenue.get(agencyId);
            agencyRevenue.put(agencyId,revenue);
        }

    }

    @Override
    public void resetStats() {
        agencyRevenue.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String,Double> agencyRevenueMap = new HashMap<>();
        for (String agencyId: agencyRevenue.keySet()){
            agencyRevenueMap.put("agencyRevenue_" + agencyId,agencyRevenue.get(agencyId));
        }

        return agencyRevenueMap;
    }
}
