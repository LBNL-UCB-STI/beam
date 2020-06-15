package beam.analysis.summary;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.IterationSummaryAnalysis;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.Map;

public class RideHailSummary implements IterationSummaryAnalysis {

    private int countOfMultiPassengerPoolTrips = 0;
    private int countOfOnePassengerPoolTrips = 0;
    private int countOfSoloTrips = 0;
    private int countOfUnmatchedPoolRequests = 0;
    private int countOfUnmatchedSoloRequests = 0;
    private double sumDeadheadingDistanceTraveled = 0.0;
    private double sumRideHailDistanceTraveled = 0.0;
    private final Map<Id<Person>, String> modeChoiceAttempt = new HashMap<Id<Person>, String>();
    private final Map<Id<Person>, Boolean> personHasSharedATrip = new HashMap<Id<Person>, Boolean>();
    private final Map<Id<Vehicle>, Integer> passengersPerVeh = new HashMap<Id<Vehicle>, Integer>();
    private final Map<Id<Person>, Id<Vehicle>> personInVeh = new HashMap<Id<Person>, Id<Vehicle>>();

    @Override
    public void processStats(Event event) {
        switch(event.getEventType()) {
            case "ModeChoice":
                ModeChoiceEvent modeChoice = (ModeChoiceEvent)event;
                if(modeChoice.getPersonId().toString().startsWith("rideHailAgent")) {
                    // do nothing, agent is driving (this test might be unnecessary)
                } else if(modeChoice.mode.startsWith("ride_hail")) {
                    modeChoiceAttempt.put(modeChoice.personId, modeChoice.mode);
                } else if(modeChoiceAttempt.containsKey(modeChoice.personId) &&
                        !modeChoiceAttempt.get(modeChoice.personId).endsWith("unmatched")) {
                    String previousMode = modeChoiceAttempt.get(modeChoice.personId);
                    modeChoiceAttempt.put(modeChoice.personId, previousMode + "_unmatched");
                }
                break;
            case "PersonEntersVehicle":
                PersonEntersVehicleEvent personEntersVehicle = (PersonEntersVehicleEvent)event;
                if(!modeChoiceAttempt.containsKey(personEntersVehicle.getPersonId())) return;
                String modeChosenL = modeChoiceAttempt.get(personEntersVehicle.getPersonId());
                if(modeChosenL.endsWith("unmatched")) {
                    if (modeChosenL.startsWith("ride_hail_pooled"))
                        countOfUnmatchedPoolRequests++;
                    else
                        countOfUnmatchedSoloRequests++;
                    modeChoiceAttempt.remove(personEntersVehicle.getPersonId());
                } else if(!personEntersVehicle.getVehicleId().toString().startsWith("rideHailVehicle")) {
                    // do nothing, agent is walking
                } else if(modeChosenL.equals("ride_hail_pooled")){
                    personInVeh.put(personEntersVehicle.getPersonId(), personEntersVehicle.getVehicleId());
                    int prev_pool = passengersPerVeh.getOrDefault(personEntersVehicle.getVehicleId(), 0);
                    passengersPerVeh.put(personEntersVehicle.getVehicleId(), prev_pool + 1);
                    for (Map.Entry<Id<Person>, Id<Vehicle>> entry : personInVeh.entrySet()) {
                        if(entry.getValue() != personEntersVehicle.getVehicleId())
                            continue;
                        if(!personHasSharedATrip.getOrDefault(entry.getKey(), false)){
                            personHasSharedATrip.put(entry.getKey(), passengersPerVeh.get(personEntersVehicle.getVehicleId()) > 1);
                        }
                    }
                } else {
                    countOfSoloTrips++;
                }
                break;
            case "PersonLeavesVehicle":
                PersonLeavesVehicleEvent personLeavesVehicle = (PersonLeavesVehicleEvent)event;
                if(!modeChoiceAttempt.containsKey(personLeavesVehicle.getPersonId())) return;
                String chosenModeL = modeChoiceAttempt.get(personLeavesVehicle.getPersonId());
                if(!personLeavesVehicle.getVehicleId().toString().startsWith("rideHailVehicle")) {
                    // do nothing, agent probably walking, although it shouldn't happen here
                } else if(chosenModeL.equals("ride_hail_pooled")) {
                    if(passengersPerVeh.get(personLeavesVehicle.getVehicleId()) > 1)
                        personHasSharedATrip.put(personLeavesVehicle.getPersonId(), true);
                    if(personHasSharedATrip.get(personLeavesVehicle.getPersonId())) {
                        countOfMultiPassengerPoolTrips++;
                    } else {
                        countOfOnePassengerPoolTrips++;
                    }
                    personHasSharedATrip.remove(personLeavesVehicle.getPersonId());
                    personInVeh.remove(personLeavesVehicle.getPersonId());
                    passengersPerVeh.put(personLeavesVehicle.getVehicleId(), passengersPerVeh.get(personLeavesVehicle.getVehicleId()) - 1);
                }
                modeChoiceAttempt.remove(personLeavesVehicle.getPersonId());
                break;
            case "PathTraversal":
                PathTraversalEvent pathTraversal = (PathTraversalEvent)event;
                if(!pathTraversal.vehicleId().toString().startsWith("rideHailVehicle")) return;
                if(pathTraversal.numberOfPassengers() < 1)
                    sumDeadheadingDistanceTraveled += pathTraversal.legLength();
                sumRideHailDistanceTraveled += pathTraversal.legLength();
                break;
            default:
        }
    }

    @Override
    public void resetStats() {
        countOfMultiPassengerPoolTrips = 0;
        countOfOnePassengerPoolTrips = 0;
        countOfSoloTrips = 0;
        countOfUnmatchedPoolRequests = 0;
        countOfUnmatchedSoloRequests = 0;
        sumDeadheadingDistanceTraveled = 0.0;
        sumRideHailDistanceTraveled = 0.0;
        modeChoiceAttempt.clear();
        personHasSharedATrip.clear();
        passengersPerVeh.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = new HashMap<String, Double>();
        double multiPassengersTripsPerPoolTrips = 0.0;
        double multiPassengersTripsPerRideHailTrips = 0.0;
        double unmatchedPerRideHailRequests = 0.0;
        double deadheadingPerRideHailTrips = 0.0;

        double totPoolTrips = countOfMultiPassengerPoolTrips + countOfOnePassengerPoolTrips;
        double totRHTrips = totPoolTrips+countOfSoloTrips;
        double totRHUnmatched = countOfUnmatchedPoolRequests+countOfUnmatchedSoloRequests;
        double totRHRequests = totRHTrips+totRHUnmatched;
        if(totPoolTrips > 0) multiPassengersTripsPerPoolTrips = countOfMultiPassengerPoolTrips /totPoolTrips;
        if(totRHTrips > 0) multiPassengersTripsPerRideHailTrips = countOfMultiPassengerPoolTrips /totRHTrips;
        if(totRHRequests > 0) unmatchedPerRideHailRequests = totRHUnmatched/totRHRequests;
        if(sumRideHailDistanceTraveled > 0) deadheadingPerRideHailTrips = sumDeadheadingDistanceTraveled/sumRideHailDistanceTraveled;
        summaryStats.put("RHSummary_multiPassengerTripsPerPoolTrips", multiPassengersTripsPerPoolTrips);
        summaryStats.put("RHSummary_multiPassengerTripsPerRideHailTrips", multiPassengersTripsPerRideHailTrips);
        summaryStats.put("RHSummary_unmatchedPerRideHailRequests", unmatchedPerRideHailRequests);
        summaryStats.put("RHSummary_deadheadingPerRideHailTrips", deadheadingPerRideHailTrips);
        return summaryStats;
    }
}
