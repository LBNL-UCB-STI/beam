package beam.analysis.summary;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleTravelTimeAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();
    private Scenario scenario;
    private int countOfVehicle = 0 ;
    double averageVehicleDelay = 0.0;
    double totalVehicleTrafficDelay = 0.0;
    double busCrowding = 0.0;

    public VehicleTravelTimeAnalysis(Scenario scenario){
        this.scenario = scenario;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
            double hoursTraveled = (Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)) -
                    Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)));
            int numOfPassangers = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            int seatingCapacity = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));

            secondsTraveledByVehicleType.merge(mode, hoursTraveled, (d1, d2) -> d1 + d2);

            if (AgentSimToPhysSimPlanConverter.isPhyssimMode(mode)){
                countOfVehicle ++;

                double freeFlowDuration = 0.0;
                Map<Id<Link>, ? extends  Link> linkslist ;
                if(scenario != null){
                    linkslist =  scenario.getNetwork().getLinks();
                    String links[] = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS).split(",");
                    for(String link:links ){
                        Id id = Id.createLinkId(link);
                        if(linkslist.containsKey(id)) {
                            double freeFlowLength = linkslist.get(id).getLength();
                            double freeFlowSpeed = linkslist.get(id).getFreespeed();
                            freeFlowDuration += freeFlowLength / freeFlowSpeed;
                        }
                    }
                }
                if(hoursTraveled > freeFlowDuration ) { //discarding negative values
                    averageVehicleDelay += numOfPassangers * (hoursTraveled - freeFlowDuration);
                    totalVehicleTrafficDelay += (hoursTraveled - freeFlowDuration);
                }
            }

            if(numOfPassangers > seatingCapacity){
                int numOfStandingPeople = numOfPassangers - seatingCapacity;
                busCrowding += hoursTraveled * numOfStandingPeople ;
            }
        }
    }

    @Override
    public void resetStats() {
        countOfVehicle = 0;
        averageVehicleDelay = 0.0;
        totalVehicleTrafficDelay = 0.0;
        secondsTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats =  secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue()/3600.0
        ));

        summaryStats.put("averageVehicleDelayPerTrip" , (averageVehicleDelay / countOfVehicle));
        summaryStats.put("totalHoursOfVehicleTrafficDelay" , totalVehicleTrafficDelay / 3600);
        summaryStats.put("busCrowding" , busCrowding/3600);
        return  summaryStats;
    }

}
