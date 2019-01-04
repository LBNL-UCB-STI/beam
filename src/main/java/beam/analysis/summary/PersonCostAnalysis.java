package beam.analysis.summary;

import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.router.Modes;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;

import java.util.HashMap;
import java.util.Map;

public class PersonCostAnalysis implements IterationSummaryAnalysis {
  private Map<String,Double> personCostByCostType = new HashMap<>();
  private String[] costTypes = {"Cost", "Incentive", "Toll"};
  private int numberOfTrips = 0;
  private double totalNetCost = 0.0;

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
            totalNetCost += cost;
            break;
          case "Incentive":
            cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_INCENTIVE));
            break;
          case "Toll":
            cost = Double.parseDouble(attributes.get(PersonCostEvent.ATTRIBUTE_TOLL_COST));
            break;
        }
        String statType = String.format("total%s_%s", costType, mode);
        personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
      }
    }
    if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
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
    personCostByCostType.put("averageTripExpenditure", totalNetCost / numberOfTrips);
    Modes.BeamMode$.MODULE$.allModes().foreach(mode -> {
      Double cost = 0.0;
      for (String costType : costTypes) {
        String statType = String.format("total%s_%s", costType, mode.value());
        personCostByCostType.merge(statType, cost, (d1, d2) -> d1 + d2);
      }
      return null;
    });

    return personCostByCostType;
  }
}
