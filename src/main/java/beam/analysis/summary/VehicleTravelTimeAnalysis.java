package beam.analysis.summary;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.network.Link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VehicleTravelTimeAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();
    private Scenario scenario;
    private int countOfHomeVehicle = 0;
    private int countOfWorkVehicle = 0;
    private int countOfSecondaryVehicle = 0;
    private double totalVehicleTrafficDelay = 0.0;
    private double busCrowding = 0.0;
    private double averageVehicleDelayWork = 0.0;
    private double averageVehicleDelayHome = 0.0;
    private double averageVehicleDelaySecondary = 0.0;
    private Map<String, Double> vehicleIdDelay = new HashMap<>();
    private Map<String, Double> personIdDelay = new HashMap<>();
    private static final String work = "Work";
    private static final String home = "Home";
    private long numOfTimesBusTaken = 0;
    private List<String> buses = new ArrayList<>();

    public VehicleTravelTimeAnalysis(Scenario scenario) {
        this.scenario = scenario;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
            double travelDurationInSec = (Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)) -
                    Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)));
            int numOfPassengers = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            int seatingCapacity = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));

            secondsTraveledByVehicleType.merge(mode, travelDurationInSec, (d1, d2) -> d1 + d2);

            if (AgentSimToPhysSimPlanConverter.isPhyssimMode(mode)) {

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
                if (travelDurationInSec > freeFlowDuration) { //discarding negative values
                    if(AgentSimToPhysSimPlanConverter.CAR.equalsIgnoreCase(mode)){
                        String vehicleID = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
                        double averageVehicleDelay = numOfPassengers * (travelDurationInSec - freeFlowDuration);
                        vehicleIdDelay.put(vehicleID, averageVehicleDelay);
                    }
                    totalVehicleTrafficDelay += (travelDurationInSec - freeFlowDuration);
                }
            }

            if(AgentSimToPhysSimPlanConverter.BUS.equalsIgnoreCase(mode)) {
                buses.add(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID));
                if (numOfPassengers > seatingCapacity) {
                    int numOfStandingPeople = numOfPassengers - seatingCapacity;
                    busCrowding += travelDurationInSec * numOfStandingPeople;
                }
            }
        } else if (event instanceof PersonLeavesVehicleEvent || event.getEventType().equalsIgnoreCase(PersonLeavesVehicleEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);
            if(buses.contains(vehicleId)) {
                numOfTimesBusTaken++;
            }

            if (vehicleIdDelay.containsKey(vehicleId)) {
                String personID = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON);
                personIdDelay.put(personID, vehicleIdDelay.get(vehicleId));
                vehicleIdDelay.remove(vehicleId);
            }
        } else if (event instanceof ActivityStartEvent || event.getEventType().equalsIgnoreCase(ActivityStartEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String personId = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_PERSON);
            if(personIdDelay.containsKey(personId)){
                String actType = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_ACTTYPE);
                if(actType.equals(work)){
                    averageVehicleDelayWork += personIdDelay.get(personId);
                    countOfWorkVehicle++;
                }
                if(actType.equals(home)){
                    averageVehicleDelayHome += personIdDelay.get(personId);
                    countOfHomeVehicle++;
                }
                else{
                    averageVehicleDelaySecondary += personIdDelay.get(personId);
                    countOfSecondaryVehicle++;
                }
                personIdDelay.remove(personId);
            }
        }
    }

    @Override
    public void resetStats() {
        numOfTimesBusTaken = 0;
        countOfHomeVehicle = 0;
        countOfWorkVehicle = 0;
        countOfSecondaryVehicle = 0;
        totalVehicleTrafficDelay = 0.0;
        busCrowding = 0.0;
        averageVehicleDelayWork = 0.0;
        averageVehicleDelayHome = 0.0;
        averageVehicleDelaySecondary = 0.0;
        secondsTraveledByVehicleType.clear();
        vehicleIdDelay.clear();
        personIdDelay.clear();
        buses.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue() / 3600.0
        ));

        summaryStats.put("averageVehicleDelayPerMotorizedLeg_work", countOfWorkVehicle !=0 ? averageVehicleDelayWork/countOfWorkVehicle : 0);
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_home", countOfHomeVehicle !=0 ? averageVehicleDelayHome/countOfHomeVehicle : 0);
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_secondary", countOfSecondaryVehicle !=0 ? averageVehicleDelaySecondary/countOfSecondaryVehicle : 0);
        summaryStats.put("totalHoursOfVehicleTrafficDelay", totalVehicleTrafficDelay / 3600);
        summaryStats.put("busCrowding", busCrowding / numOfTimesBusTaken);
        return summaryStats;
    }
}
