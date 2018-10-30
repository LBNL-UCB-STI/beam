package beam.analysis.plots;

import beam.agentsim.events.PersonCostEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.util.HashMap;
import java.util.Map;

public class PersonCostStats implements BeamStats, IterationSummaryStats {
    private Map<String,Double> personCostByCostType = new HashMap<>();

    @Override
    public void processStats(Event event) {
        if (event instanceof PersonCostEvent || event.getEventType().equalsIgnoreCase(PersonCostEvent.EVENT_TYPE)){
            Map<String, String> attributes = event.getAttributes();
            String mode = attributes.get(PersonCostEvent.ATTRIBUTE_MODE);
            String costType = attributes.get(PersonCostEvent.ATTRIBUTE_COST_TYPE);
            double cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_COST));
            String statType = String.format("total%s_%s", costType, mode);

            personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
        }

    }

    @Override
    public void createGraph(IterationEndsEvent event) {

    }

    @Override
    public void resetStats() {
        personCostByCostType.clear();
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        return personCostByCostType;
    }
}
