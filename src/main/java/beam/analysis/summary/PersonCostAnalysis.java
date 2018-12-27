package beam.analysis.summary;

import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.router.Modes;
import org.matsim.api.core.v01.events.Event;

import java.util.HashMap;
import java.util.Map;

public class PersonCostAnalysis implements IterationSummaryAnalysis {
  private Map<String, Double> personCostByCostType = new HashMap<>();
  private String[] costTypes = {"Cost", "Subsidy", "Toll"};

  @Override
  public void processStats(Event event) {
    if (event instanceof PersonCostEvent || event.getEventType().equalsIgnoreCase(PersonCostEvent.EVENT_TYPE)) {
      Map<String, String> attributes = event.getAttributes();
      String mode = attributes.get(PersonCostEvent.ATTRIBUTE_MODE);
      Double cost = 0.0;
      for (String costType : costTypes) {
        switch (costType) {
          case "Cost":
            cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_NET_COST));
            break;
          case "Subsidy":
            cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_SUBSIDY));
            break;
          case "Toll":
            cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_TOLL_COST));
            break;
        }
        String statType = String.format("total%s_%s", costType, mode);
        personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
      }
    }
  }

  @Override
  public void resetStats() {
    personCostByCostType.clear();
  }

  @Override
  public Map<String, Double> getSummaryStats() {
    Modes.BeamMode$.MODULE$.analysisModes().foreach(m -> {
      Double cost = 0.0;
      for (String costType : costTypes) {
        String statType = String.format("total%s_%s", costType, m.value());
        personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
      }
      return null;
    });

    return personCostByCostType;
  }
}
