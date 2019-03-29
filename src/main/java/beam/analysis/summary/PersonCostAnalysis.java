package beam.analysis.summary;

import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.router.Modes;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;

import java.util.HashMap;
import java.util.Map;

public class PersonCostAnalysis implements IterationSummaryAnalysis {
  private Map<String,Double> personCostByCostType = new HashMap<>();
  private Map<String, Integer> personCostCount = new HashMap<>();
  private Map<String,Double> personCostByActivityType = new HashMap<>();
  private String[] costTypes = {"Cost", "Incentive", "Toll"};
  private Map<String, Integer> activityTypeCount = new HashMap<>();
  private Map<String, Double> personIdCost = new HashMap<>();
  private int numberOfTrips = 0;
  private double totalNetCost = 0.0;

  @Override
  public void processStats(Event event) {
    if (event instanceof PersonCostEvent || event.getEventType().equalsIgnoreCase(PersonCostEvent.EVENT_TYPE)) {
      PersonCostEvent pce = (PersonCostEvent)event;
      String mode = pce.getMode();
      Double cost = 0.0;
      for (String costType : costTypes) {
        switch (costType) {
          case "Cost":
            cost = pce.getNetCost();
            totalNetCost += cost;
            personIdCost.put(pce.getPersonId().toString(), cost);
            break;
          case "Incentive":
            cost = pce.getIncentive();
            break;
          case "Toll":
            cost = pce.getTollCost();
            break;
        }
        String statType = String.format("total%s_%s", costType, mode);
        personCostByCostType.merge(statType, cost, Double::sum);
        personCostCount.merge(statType, 1, Integer::sum);
      }
    }
    if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
      numberOfTrips++;
    }
    if (event instanceof ActivityStartEvent || event.getEventType().equalsIgnoreCase(ActivityStartEvent.EVENT_TYPE)) {
      ActivityStartEvent ase = (ActivityStartEvent) event;
      String personId = ase.getPersonId().toString();
      if(personIdCost.containsKey(personId)){
        String actType = ase.getActType();
        String statType = String.format("averageTripExpenditure_%s", actType);
        double cost = personIdCost.get(personId);
        personCostByActivityType.merge(statType, cost, (d1, d2) -> d1 + d2);
        activityTypeCount.merge(statType, 1, Integer::sum);
        personIdCost.remove(personId);
      }
    }
  }

  @Override
  public void resetStats() {
    personIdCost.clear();
    activityTypeCount.clear();
    personCostByActivityType.clear();
    personCostCount.clear();
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
        if(personCostCount.containsKey(statType)){
          cost = personCostByCostType.get(statType);
        }
        personCostByCostType.put(statType, cost);
      }
      return null;
    });
    activityTypeCount.keySet().forEach(key ->
          personCostByCostType.put(key, personCostByActivityType.getOrDefault(key, 0D) / activityTypeCount.get(key))

    );
    return personCostByCostType;
  }
}
