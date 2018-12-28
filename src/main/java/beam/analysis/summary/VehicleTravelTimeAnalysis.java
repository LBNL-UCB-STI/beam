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
    private int countOfVehicle = 0;
    private double averageVehicleDelay = 0.0;
    private double totalVehicleTrafficDelay = 0.0;
    private double busCrowding = 0.0;
    private long numOfPathTraversalsWithBusMode = 0;

    public VehicleTravelTimeAnalysis(Scenario scenario) {
        this.scenario = scenario;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
            double hoursTraveled = (Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)) -
                    Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)));
            int numOfPassengers = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            int seatingCapacity = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));

            secondsTraveledByVehicleType.merge(mode, hoursTraveled, (d1, d2) -> d1 + d2);

            if (AgentSimToPhysSimPlanConverter.isPhyssimMode(mode)) {
                countOfVehicle++;

                double freeFlowDuration = 0.0;
                Map<Id<Link>, ? extends Link> linksMap;
                if (scenario != null) {
                    linksMap = scenario.getNetwork().getLinks();
                    String links[] = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS).split(",");
                    for (String linkId : links) {
                        Link link = linksMap.get(Id.createLinkId(linkId));
                        if (link != null) {
                            double freeFlowLength = link.getLength();
                            double freeFlowSpeed = link.getFreespeed();
                            freeFlowDuration += freeFlowLength / freeFlowSpeed;
                        }
                    }
                }
                if (hoursTraveled > freeFlowDuration) { //discarding negative values
                    averageVehicleDelay += numOfPassengers * (hoursTraveled - freeFlowDuration);
                    totalVehicleTrafficDelay += (hoursTraveled - freeFlowDuration);
                }
            }

            if(AgentSimToPhysSimPlanConverter.BUS.equalsIgnoreCase(mode)) {
                numOfPathTraversalsWithBusMode++;
                if (numOfPassengers > seatingCapacity) {
                    int numOfStandingPeople = numOfPassengers - seatingCapacity;
                    busCrowding += hoursTraveled * numOfStandingPeople;
                }
            }
        }
    }

    @Override
    public void resetStats() {
        numOfPathTraversalsWithBusMode = 0;
        countOfVehicle = 0;
        averageVehicleDelay = 0.0;
        totalVehicleTrafficDelay = 0.0;
        busCrowding = 0.0;
        secondsTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue() / 3600.0
        ));

        summaryStats.put("averageVehicleDelayPerTrip", (averageVehicleDelay / countOfVehicle));
        summaryStats.put("totalHoursOfVehicleTrafficDelay", totalVehicleTrafficDelay / 3600);
        summaryStats.put("busCrowding", busCrowding / 3600 / numOfPathTraversalsWithBusMode);
        return summaryStats;
    }

}
