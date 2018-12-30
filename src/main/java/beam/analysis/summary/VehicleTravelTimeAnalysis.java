package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.ListUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.network.Link;

import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Math.max;

public class VehicleTravelTimeAnalysis implements IterationSummaryAnalysis {
    private Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();
	private scala.collection.Set<Id<BeamVehicleType>> vehicleTypes;
    private Scenario scenario;
    private int countOfHomeVehicle = 0;
    private int countOfWorkVehicle = 0;
    private int countOfSecondaryVehicle = 0;
    private double totalVehicleTrafficDelay = 0.0;
    private double busCrowding = 0.0;
    private double totalVehicleDelayWork = 0.0;
    private double totalVehicleDelayHome = 0.0;
    private double totalVehicleDelaySecondary = 0.0;
    private Map<String, Double> personIdDelay = new HashMap<>();
    private Map<String, List<String>> personsByVehicleIds = new HashMap<>();
    private static final String work = "Work";
    private static final String home = "Home";
    private long numOfTimesBusTaken = 0;
    private Set<String> buses = new HashSet<>();


    public VehicleTravelTimeAnalysis(Scenario scenario, scala.collection.Set<Id<BeamVehicleType>> vehicleTypes) {
        this.scenario = scenario;
		this.vehicleTypes = vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PersonEntersVehicleEvent || event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);
            String personId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON);

            personsByVehicleIds.merge(vehicleId, Lists.newArrayList(personId), ListUtils::union);
        } else if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String mode = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
            double travelDurationInSec = (Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)) -
                    Double.parseDouble(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)));
            int numOfPassengers = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
            int seatingCapacity = Integer.parseInt(eventAttributes.get(PathTraversalEvent.ATTRIBUTE_SEATING_CAPACITY));

            secondsTraveledByVehicleType.merge(mode, travelDurationInSec, Double::sum);

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

                    String vehicleID = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
                    double averageVehicleDelay = travelDurationInSec - freeFlowDuration;

                    if(personsByVehicleIds.containsKey(vehicleID)) {
                        personsByVehicleIds.get(vehicleID).forEach(personId -> personIdDelay.merge(personId, averageVehicleDelay, Double::sum));
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
            personsByVehicleIds.remove(vehicleId);
        } else if (event instanceof ActivityStartEvent || event.getEventType().equalsIgnoreCase(ActivityStartEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String personId = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_PERSON);
            if(personIdDelay.containsKey(personId)){
                String actType = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_ACTTYPE);
                if(actType.equals(work)){
                    totalVehicleDelayWork += personIdDelay.get(personId);
                    countOfWorkVehicle++;
                }
                if(actType.equals(home)){
                    totalVehicleDelayHome += personIdDelay.get(personId);
                    countOfHomeVehicle++;
                }
                else{
                    totalVehicleDelaySecondary += personIdDelay.get(personId);
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
        totalVehicleDelayWork = 0.0;
        totalVehicleDelayHome = 0.0;
        totalVehicleDelaySecondary = 0.0;
        secondsTraveledByVehicleType.clear();
        personsByVehicleIds.clear();
        personIdDelay.clear();
        buses.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" +  e.getKey(),
                e -> e.getValue() / 3600.0
        ));
		
		vehicleTypes.foreach(vt -> summaryStats.merge("vehicleHoursTraveled_" + vt.toString(),0D, Double::sum));

        summaryStats.put("averageVehicleDelayPerMotorizedLeg_work", totalVehicleDelayWork / max(countOfWorkVehicle, 1));
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_home", totalVehicleDelayHome / max(countOfHomeVehicle, 1));
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_secondary", totalVehicleDelaySecondary / max(countOfSecondaryVehicle, 1));
        summaryStats.put("totalHoursOfVehicleTrafficDelay", totalVehicleTrafficDelay / 3600);
        summaryStats.put("busCrowding", busCrowding / max(numOfTimesBusTaken, 1));
        return summaryStats;
    }
}
