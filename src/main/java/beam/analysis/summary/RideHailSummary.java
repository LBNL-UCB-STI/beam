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

    private int countOfPoolTrips = 0;
    private int countOfSoloPoolTrips = 0;
    private int countOfSoloTrips = 0;
    private int countOfUnmatchedPoolRequests = 0;
    private int countOfUnmatchedSoloRequests = 0;
    private double sumDeadheadingDistanceTraveled = 0.0;
    private double sumRideHailDistanceTraveled = 0.0;

    private Map<Id<Person>, String> latestModeChoiceAttempt = new HashMap<Id<Person>, String>();
    private Map<Id<Person>, Boolean> personSharedTrip = new HashMap<Id<Person>, Boolean>();
    private Map<Id<Vehicle>, Integer> withinPooledTrip = new HashMap<Id<Vehicle>, Integer>();

    @Override
    public void processStats(Event event) {
        switch(event.getEventType()) {
            case "ModeChoice":
                ModeChoiceEvent modeChoice = (ModeChoiceEvent)event;
                if(modeChoice.mode.contains("ride_hail")) {
                    latestModeChoiceAttempt.put(modeChoice.personId, modeChoice.mode);
                } else if(latestModeChoiceAttempt.containsKey(modeChoice.personId) &&
                        !latestModeChoiceAttempt.get(modeChoice.personId).contains("unmatched")) {
                    String previousMode = latestModeChoiceAttempt.get(modeChoice.personId);
                    latestModeChoiceAttempt.put(modeChoice.personId, previousMode + "_unmatched");
                }
                break;
            case "PersonEntersVehicle":
                PersonEntersVehicleEvent personEntersVehicle = (PersonEntersVehicleEvent)event;
                Id<Vehicle> idVehE = personEntersVehicle.getVehicleId();
                Id<Person> idPerE = personEntersVehicle.getPersonId();
                if(!latestModeChoiceAttempt.containsKey(idPerE)) return;
                String modeChosenL = latestModeChoiceAttempt.get(idPerE);
                if(modeChosenL.contains("unmatched")) {
                    if(modeChosenL.contains("ride_hail_pooled")) countOfUnmatchedPoolRequests++;
                    else countOfUnmatchedSoloRequests++;
                } else if(modeChosenL.equals("ride_hail_pooled")){
                    personSharedTrip.put(idPerE, false);
                    withinPooledTrip.putIfAbsent(idVehE, 0);
                    withinPooledTrip.put(idVehE, withinPooledTrip.get(idVehE) + 1);
                    if(withinPooledTrip.get(idVehE) > 1) personSharedTrip.put(idPerE, true);
                } else {
                    countOfSoloTrips++;
                }
                break;
            case "PersonLeavesVehicle":
                PersonLeavesVehicleEvent personLeavesVehicle = (PersonLeavesVehicleEvent)event;
                Id<Vehicle> idVehL = personLeavesVehicle.getVehicleId();
                Id<Person> idPerL = personLeavesVehicle.getPersonId();
                if(!idVehL.toString().contains("rideHailVehicle") || idPerL.toString().contains("rideHailAgent")) return;
                String chosenModeL = latestModeChoiceAttempt.get(personLeavesVehicle.getPersonId());
                if(! (chosenModeL==null ) && chosenModeL.equals("ride_hail_pooled")) {
                    if(withinPooledTrip.get(idVehL) > 1) personSharedTrip.put(idPerL, true);
                    if(personSharedTrip.get(idPerL)) {
                        countOfPoolTrips++;
                    } else {
                        countOfSoloPoolTrips++;
                    }
                }
                latestModeChoiceAttempt.remove(personLeavesVehicle.getPersonId());
                personSharedTrip.remove(personLeavesVehicle.getPersonId());
                if (idVehL != null && withinPooledTrip.get(idVehL) != null) {
                    withinPooledTrip.put(idVehL, withinPooledTrip.get(idVehL) - 1);
                }
                break;
            case "PathTraversal":
                PathTraversalEvent pathTraversal = (PathTraversalEvent)event;
                if(!pathTraversal.vehicleId().toString().contains("rideHailVehicle")) return;
                if(pathTraversal.numberOfPassengers() < 1) sumDeadheadingDistanceTraveled += pathTraversal.legLength();
                sumRideHailDistanceTraveled += pathTraversal.legLength();
                break;
            default:
        }
    }

    @Override
    public void resetStats() {
        countOfPoolTrips = 0;
        countOfSoloPoolTrips = 0;
        countOfSoloTrips = 0;
        countOfUnmatchedPoolRequests = 0;
        countOfUnmatchedSoloRequests = 0;
        sumDeadheadingDistanceTraveled = 0.0;
        sumRideHailDistanceTraveled = 0.0;
        latestModeChoiceAttempt.clear();
        personSharedTrip.clear();
        withinPooledTrip.clear();
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        Map<String, Double> summaryStats = new HashMap<String, Double>();
        double multiPassengersTripsPerPoolTrips = 0.0;
        double multiPassengersTripsPerRideHailTrips = 0.0;
        double unmatchedPerRideHailRequests = 0.0;
        double deadheadingPerRideHailTrips = 0.0;

        double totPoolTrips = countOfPoolTrips+countOfSoloPoolTrips;
        double totRHTrips = totPoolTrips+countOfSoloTrips;
        double totRHUnmatched = countOfUnmatchedPoolRequests+countOfUnmatchedSoloRequests;
        double totRHRequests = totRHTrips+totRHUnmatched;
        if(totPoolTrips > 0) multiPassengersTripsPerPoolTrips = countOfPoolTrips/totPoolTrips;
        if(totRHTrips > 0) multiPassengersTripsPerRideHailTrips = countOfPoolTrips/totRHTrips;
        if(totRHRequests > 0) unmatchedPerRideHailRequests = totRHUnmatched/totRHRequests;
        if(sumRideHailDistanceTraveled > 0) deadheadingPerRideHailTrips = sumDeadheadingDistanceTraveled/sumRideHailDistanceTraveled;
        summaryStats.put("RHSummary_multiPassengerTripsPerPoolTrips", multiPassengersTripsPerPoolTrips);
        summaryStats.put("RHSummary_multiPassengerTripsPerRideHailTrips", multiPassengersTripsPerRideHailTrips);
        summaryStats.put("RHSummary_unmatchedPerRideHailRequests", unmatchedPerRideHailRequests);
        summaryStats.put("RHSummary_deadheadingPerRideHailTrips", deadheadingPerRideHailTrips);
        return summaryStats;
    }
}
