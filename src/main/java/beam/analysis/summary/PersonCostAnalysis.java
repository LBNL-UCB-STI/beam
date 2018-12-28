package beam.analysis.summary;

import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;

import java.util.HashMap;
import java.util.Map;

public class PersonCostAnalysis implements IterationSummaryAnalysis {
    private Map<String,Double> personCostByCostType = new HashMap<>();
    private String[] costTypes = {"Cost","Subsidy","Toll"};
    private int numberOfTrips = 0;
    double totalNetCost = 0.0;

    @Override
    public void processStats(Event event) {
        if (event instanceof PersonCostEvent || event.getEventType().equalsIgnoreCase(PersonCostEvent.EVENT_TYPE)){
            Map<String, String> attributes = event.getAttributes();
            String mode = attributes.get(PersonCostEvent.ATTRIBUTE_MODE);
            Double cost = 0.0;
            for(String costType : costTypes){
                if(costType.equals("Cost")){
                    cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_NET_COST));
                    totalNetCost += cost;
                }else if(costType.equals("Subsidy")){
                    cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_SUBSIDY));
                }else if(costType.equals("Toll")) {
                    cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_TOLL_COST));
                }
                String statType = String.format("total%s_%s", costType, mode);
                personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
            }
        }
        if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)){
            numberOfTrips++;
        }

    }

    @Override
    public void resetStats() {
        personCostByCostType.clear();
        numberOfTrips = 0;
        totalNetCost = 0;
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        personCostByCostType.put("averageTripExpenditure", totalNetCost /numberOfTrips);
        return personCostByCostType;
    }
}
