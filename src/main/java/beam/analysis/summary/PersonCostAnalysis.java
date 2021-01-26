package beam.analysis.summary;

import beam.agentsim.events.PersonCostEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.router.Modes;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.households.Household;
import org.matsim.households.Households;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class PersonCostAnalysis implements IterationSummaryAnalysis {
  private final Map<Id<Person>, Household> personToHousehold;
  private final Map<String,Double> personCostByCostType = new HashMap<>();
  private final Map<String, Integer> personCostCount = new HashMap<>();
  private final Map<String,Double> personCostByActivityType = new HashMap<>();
  private final String[] costTypes = {"Cost", "Incentive", "Toll"};
  private final Map<String, Integer> activityTypeCount = new HashMap<>();
  private final Map<String, Double> personIdCost = new HashMap<>();
  private final Map<String, Double> personDepartureTime = new HashMap<>();
  private int numberOfTrips = 0;
  private double totalNetCost = 0.0;
  private final BeamServices beamServices;
  double averageVot=0;
  final String votKeyString="valueOfTime";
  final double defaultDummyHouseholdIncome=0.01;


  private final Logger logger = LoggerFactory.getLogger(PersonCostAnalysis.class);

  public PersonCostAnalysis(BeamServices beamServices){
    this.beamServices = beamServices;
    averageVot=getAverageVOT();

    if(beamServices.matsimServices().getScenario().getHouseholds().getHouseholds().values().stream().filter((Household hh) -> hh.getIncome().getIncome()!=0).count() != beamServices.matsimServices().getScenario().getHouseholds().getHouseholds().size()){
      logger.error("Some households have income not set - default dummy income values will be used: "+ defaultDummyHouseholdIncome);
    }
    this.personToHousehold = buildServicesPersonHouseholds(beamServices.matsimServices().getScenario().getHouseholds());
  }

  private Map<Id<Person>, Household> buildServicesPersonHouseholds(Households households) {
    Map<Id<Person>, Household> personToHousehold = new HashMap<>();
    households.getHouseholds().values().forEach(h -> h.getMemberIds().forEach(id -> {
        personToHousehold.put(id, h);
      }));
    return personToHousehold;
  }

  private double getAverageVOT(){
    Population pop=beamServices.matsimServices().getScenario().getPopulation();
    double sumOfVOT=0;
    for (Id<Person> personId:pop.getPersons().keySet()){
      if (pop.getPersonAttributes().getAttribute(personId.toString(),votKeyString)==null){
        double defaultValueOfTime=beamServices.beamConfig().beam().agentsim().agents().modalBehaviors().defaultValueOfTime();
        logger.warn("using defaultValueOfTime, as VOT not set in person attribute - :" + defaultValueOfTime);
        return defaultValueOfTime;
      } else {
        sumOfVOT+=Double.parseDouble(pop.getPersonAttributes().getAttribute(personId.toString(),votKeyString).toString());
      }
    }

    return sumOfVOT/pop.getPersons().size();
  }

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
      PersonDepartureEvent pde = (PersonDepartureEvent)event;
      personDepartureTime.put(pde.getPersonId().toString(), pde.getTime());
      numberOfTrips++;
    }
    if (event instanceof ActivityStartEvent || event.getEventType().equalsIgnoreCase(ActivityStartEvent.EVENT_TYPE)) {
      ActivityStartEvent ase = (ActivityStartEvent) event;
      Household householdOption = personToHousehold.get(ase.getPersonId());
      if (householdOption != null) {
        String personId = ase.getPersonId().toString();
        double householdIncome=householdOption.getIncome().getIncome();
        if (householdIncome==0){
          householdIncome=defaultDummyHouseholdIncome;
        }

        if(personIdCost.containsKey(personId)){
          String actType = ase.getActType();
          String statType = String.format("averageTripExpenditure_%s", actType);
          double cost = personIdCost.get(personId);
          double travelTimeInHours=(ase.getTime()-personDepartureTime.get(personId))/3600;
          double personGeneralizedCostByAct=(cost+travelTimeInHours*averageVot)/householdIncome;
          personCostByActivityType.merge(statType, personGeneralizedCostByAct, (d1, d2) -> d1 + d2);
          activityTypeCount.merge(statType, 1, Integer::sum);
          personIdCost.remove(personId);
        }
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
