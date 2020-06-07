package beam.analysis.summary;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter;
import beam.utils.NetworkHelper;
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

    private static final String work = "Work";
    private static final String home = "Home";

    private final scala.collection.Set<Id<BeamVehicleType>> vehicleTypes;

    private final Map<String, Double> secondsTraveledByVehicleType = new HashMap<>();
    private final Scenario scenario;
    private final NetworkHelper networkHelper;

    private double totalVehicleTrafficDelay = 0.0;
    private final Set<String> buses = new HashSet<>();
    private double busCrowding = 0.0;
    private long numOfTimesBusTaken = 0;

    private int countOfHomeVehicle = 0;
    private int countOfWorkVehicle = 0;
    private int countOfSecondaryVehicle = 0;
    private int numberOfPassengerTrip = 0;
    private double totalVehicleDelayWork = 0.0;
    private double totalVehicleDelayHome = 0.0;
    private double totalVehicleDelaySecondary = 0.0;
    private double totalVehicleDelay = 0.0;
    private final Map<String, List<Double>> personIdDelays = new HashMap<>();
    private final Map<String, List<String>> personsByVehicleIds = new HashMap<>();

    public VehicleTravelTimeAnalysis(Scenario scenario, NetworkHelper networkHelper, scala.collection.Set<Id<BeamVehicleType>> vehicleTypes) {
        this.scenario = scenario;
        this.networkHelper = networkHelper;
        this.vehicleTypes = vehicleTypes;
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PersonEntersVehicleEvent || event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);
            String personId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON);

            personsByVehicleIds.merge(vehicleId, Lists.newArrayList(personId), ListUtils::union);
        } else if (event instanceof PathTraversalEvent) {
            PathTraversalEvent pte = (PathTraversalEvent) event;
            String mode = pte.mode().value();
            String vehicleTypes = pte.vehicleType();
            double travelDurationInSec = pte.arrivalTime() - pte.departureTime();
            int numOfPassengers = pte.numberOfPassengers();
            int seatingCapacity = pte.seatingCapacity();

            secondsTraveledByVehicleType.merge(vehicleTypes, travelDurationInSec, Double::sum);

            if (AgentSimToPhysSimPlanConverter.isPhyssimMode(mode)) {

                double freeFlowDuration = 0.0;
                Map<Id<Link>, ? extends Link> linksMap;
                if (scenario != null) {
                    // FIXME Is there any better way to to have `Object` ??
                    for (Object linkIdObj : pte.linkIdsJava()) {
                        int linkId = (int) linkIdObj;
                        Link link = networkHelper.getLinkUnsafe(linkId);
                        if (link != null) {
                            double freeFlowLength = link.getLength();
                            double freeFlowSpeed = link.getFreespeed();
                            freeFlowDuration += freeFlowLength / freeFlowSpeed;
                        }
                    }
                }
                if (travelDurationInSec > freeFlowDuration) { //discarding negative values

                    String vehicleID = pte.vehicleId().toString();
                    double averageVehicleDelay = travelDurationInSec - freeFlowDuration;
                    totalVehicleDelay += averageVehicleDelay;

                    if (personsByVehicleIds.containsKey(vehicleID)) {
                        personsByVehicleIds.get(vehicleID).forEach(personId -> personIdDelays.merge(personId, Lists.newArrayList(averageVehicleDelay), ListUtils::union));
                    }

                    totalVehicleTrafficDelay += (travelDurationInSec - freeFlowDuration);
                    numberOfPassengerTrip++;
                }
            }

            if (AgentSimToPhysSimPlanConverter.BUS.equalsIgnoreCase(mode)) {
                buses.add(pte.vehicleId().toString());

                int numberOfSeatedPassengers = Math.min(numOfPassengers, seatingCapacity);
                int numOfStandingPassengers = Math.max(0, numOfPassengers - seatingCapacity);
                double tSeated = numOfPassengers > seatingCapacity ? 1.1 * Math.log(numOfPassengers / seatingCapacity) : 1;
                double tStanding = numOfPassengers > seatingCapacity ? 1.1 + 2.5 * Math.log(numOfPassengers / seatingCapacity) : 0;
                busCrowding += travelDurationInSec * (tSeated * numberOfSeatedPassengers + tStanding * numOfStandingPassengers);
            }
        } else if (event instanceof PersonLeavesVehicleEvent || event.getEventType().equalsIgnoreCase(PersonLeavesVehicleEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String vehicleId = eventAttributes.get(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);
            if (buses.contains(vehicleId)) {
                numOfTimesBusTaken++;
            }
            personsByVehicleIds.remove(vehicleId);
        } else if (event instanceof ActivityStartEvent || event.getEventType().equalsIgnoreCase(ActivityStartEvent.EVENT_TYPE)) {
            Map<String, String> eventAttributes = event.getAttributes();
            String personId = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_PERSON);
            if (personIdDelays.containsKey(personId)) {
                double totalDelay = personIdDelays.get(personId).stream().reduce(Double::sum).orElse(0D);
                int totalVehicles = personIdDelays.get(personId).size();
                String actType = eventAttributes.get(ActivityStartEvent.ATTRIBUTE_ACTTYPE);
                if (actType.equals(work)) {
                    totalVehicleDelayWork += totalDelay;
                    countOfWorkVehicle += totalVehicles;
                }
                if (actType.equals(home)) {
                    totalVehicleDelayHome += totalDelay;
                    countOfHomeVehicle += totalVehicles;
                } else {
                    totalVehicleDelaySecondary += totalDelay;
                    countOfSecondaryVehicle += totalVehicles;
                }
                personIdDelays.remove(personId);
            }
        }
    }

    @Override
    public void resetStats() {
        numOfTimesBusTaken = 0;
        countOfHomeVehicle = 0;
        countOfWorkVehicle = 0;
        numberOfPassengerTrip = 0;
        countOfSecondaryVehicle = 0;
        totalVehicleTrafficDelay = 0.0;
        busCrowding = 0.0;
        totalVehicleDelayWork = 0.0;
        totalVehicleDelayHome = 0.0;
        totalVehicleDelaySecondary = 0.0;
        totalVehicleDelay = 0.0;

        personsByVehicleIds.clear();
        personIdDelays.clear();
        buses.clear();
        secondsTraveledByVehicleType.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = secondsTraveledByVehicleType.entrySet().stream().collect(Collectors.toMap(
                e -> "vehicleHoursTraveled_" + e.getKey(),
                e -> e.getValue() / 3600.0
        ));

        vehicleTypes.foreach(vt -> summaryStats.merge("vehicleHoursTraveled_" + vt.toString(), 0D, Double::sum));

        summaryStats.put("averageVehicleDelayPerMotorizedLeg_work", totalVehicleDelayWork / max(countOfWorkVehicle, 1));
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_home", totalVehicleDelayHome / max(countOfHomeVehicle, 1));
        summaryStats.put("averageVehicleDelayPerMotorizedLeg_secondary", totalVehicleDelaySecondary / max(countOfSecondaryVehicle, 1));
        summaryStats.put("averageVehicleDelayPerPassengerTrip", totalVehicleDelay / numberOfPassengerTrip);
        summaryStats.put("totalHoursOfVehicleTrafficDelay", totalVehicleTrafficDelay / 3600);
        summaryStats.put("busCrowding", busCrowding / max(numOfTimesBusTaken, 1));
        return summaryStats;
    }
}
