package beam.analysis.plots;

import beam.agentsim.events.ReserveRideHailEvent;
import beam.agentsim.infrastructure.TAZTreeMap;
import beam.sim.BeamServices;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RideHailWaitingTazAnalysis implements GraphAnalysis {
    private static double numberOfTimeBins;
    private Map<String, Event> rideHailWaitingQueue = new HashMap<>();
    private Map<Tuple<Integer,Id<TAZTreeMap.TAZ>>, List<Double>> binWaitingTimesMap = new HashMap<>();
    private BeamServices beamServices;

    public RideHailWaitingTazAnalysis(BeamServices beamServices) {
        this.beamServices = beamServices;
        numberOfTimeBins = beamServices.beamConfig().beam().agentsim().timeBinSize();
    }

    /**
     * Creates graph on the notified iteration.
     * @param iterationEndsEvent a notified iteration ended event
     * @throws IOException exception
     */
    @Override
    public void createGraph(IterationEndsEvent iterationEndsEvent) throws IOException {

    }

    /**
     * Processes stats from the notified event
     * @param event An occurred event
     */
    @Override
    public void processStats(Event event) {

        /* When a person reserves a ride hail, push the person into the ride hail waiting queue and once the person
        enters a vehicle , compute the difference between the times of occurrence for both the events as `waiting time`.*/
        if (event instanceof ReserveRideHailEvent) {
            ReserveRideHailEvent reserveRideHailEvent = (ReserveRideHailEvent) event;
            Id<Person> personId = reserveRideHailEvent.getPersonId();
            //push person into the ride hail waiting queue
            rideHailWaitingQueue.put(personId.toString(), event);
        } else if (event instanceof PersonEntersVehicleEvent) {
            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent) event;
            Id<Person> personId = personEntersVehicleEvent.getPersonId();
            String _personId = personId.toString();
            Map<String, String> personEntersVehicleEventAttributes = event.getAttributes();
            if (rideHailWaitingQueue.containsKey(personId.toString()) && personEntersVehicleEventAttributes.get("vehicle").contains("rideHailVehicle")) {
                //process and add the waiting time to the total time spent by all the passengers on waiting for a ride hail
                ReserveRideHailEvent reserveRideHailEvent = (ReserveRideHailEvent) rideHailWaitingQueue.get(_personId);
                Map<String, String> reserveRideHailEventAttributes = reserveRideHailEvent.getAttributes();
                TAZTreeMap.TAZ pickUpLocationTAZ = beamServices.tazTreeMap().getTAZ(Double.parseDouble(reserveRideHailEventAttributes.get("startX")),
                        Double.parseDouble(reserveRideHailEventAttributes.get("startY")));
                double waitingTime = personEntersVehicleEvent.getTime() - reserveRideHailEvent.getTime();
                processRideHailWaitingTimesAndTaz(reserveRideHailEvent, waitingTime,pickUpLocationTAZ);
                // Remove the passenger from the waiting queue , as the passenger entered the vehicle.
                rideHailWaitingQueue.remove(_personId);
            }
        }
    }

    /**
     * Adds the given waiting time & TAZ to the list of cumulative waiting times per bin.
     * @param event An occurred event
     * @param waitingTime time spent by the passenger on waiting for the vehicle
     * @param taz TAZ of the passenger pickup location
     */
    private void processRideHailWaitingTimesAndTaz(Event event, double waitingTime,TAZTreeMap.TAZ taz) {
        //get the hour of occurrence of the event
        int hourOfEventOccurrence = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        //Add new / update the waiting time details to the list of cumulative waiting times per bin
        Tuple<Integer, Id<TAZTreeMap.TAZ>> tuple = new Tuple<>(hourOfEventOccurrence, taz.tazId());
        List<Double> timeList = binWaitingTimesMap.getOrDefault(tuple,new ArrayList<>());
        timeList.add(waitingTime);
        binWaitingTimesMap.put(tuple, timeList);
    }

    @Override
    public void resetStats() {
        binWaitingTimesMap.clear();
        rideHailWaitingQueue.clear();
    }
}
