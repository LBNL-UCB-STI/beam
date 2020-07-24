package beam.analysis.plots;

import beam.agentsim.events.ReserveRideHailEvent;
import beam.agentsim.infrastructure.taz.TAZ;
import beam.sim.BeamServices;
import beam.utils.MathUtils;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.DoubleStream;

public class RideHailWaitingTazAnalysis implements GraphAnalysis {
    private final Logger log = LoggerFactory.getLogger(RideHailWaitingTazAnalysis.class);

    private final Map<String, Event> rideHailWaitingQueue = new HashMap<>();
    private final Map<Tuple<Integer,Id<TAZ>>, List<Double>> binWaitingTimesMap = new HashMap<>();
    private final BeamServices beamServices;
    private final OutputDirectoryHierarchy ioController;

    public RideHailWaitingTazAnalysis(BeamServices beamServices, OutputDirectoryHierarchy ioController) {
        this.beamServices = beamServices;
        this.ioController = ioController;
    }

    /**
     * Creates graph on the notified iteration.
     * @param iterationEndsEvent a notified iteration ended event
     * @throws IOException exception
     */
    @Override
    public void createGraph(IterationEndsEvent iterationEndsEvent) {
        writeToCsv(iterationEndsEvent.getIteration(),binWaitingTimesMap);
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
            if (rideHailWaitingQueue.containsKey(personId.toString()) && personEntersVehicleEvent.getVehicleId().toString().contains("rideHailVehicle")) {
                //process and add the waiting time to the total time spent by all the passengers on waiting for a ride hail
                ReserveRideHailEvent reserveRideHailEvent = (ReserveRideHailEvent) rideHailWaitingQueue.get(_personId);
                Coord pickupCoord = beamServices.geo().wgs2Utm(new Coord(reserveRideHailEvent.originX, reserveRideHailEvent.originY));
                TAZ pickUpLocationTAZ = beamServices.beamScenario().tazTreeMap().getTAZ(pickupCoord.getX(),pickupCoord.getY());
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
    private void processRideHailWaitingTimesAndTaz(Event event, double waitingTime,TAZ taz) {
        //get the hour of occurrence of the event
        int hourOfEventOccurrence = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        //Add new / update the waiting time details to the list of cumulative waiting times per bin
        Tuple<Integer, Id<TAZ>> tuple = new Tuple<>(hourOfEventOccurrence, taz.tazId());
        List<Double> timeList = binWaitingTimesMap.getOrDefault(tuple,new ArrayList<>());
        timeList.add(waitingTime);
        binWaitingTimesMap.put(tuple, timeList);
    }

    /**
     * Write output to a csv file
     * @param iterationNumber Current iteration number
     * @param dataMap data to be written to the csv file
     */
    private void writeToCsv(int iterationNumber,Map<Tuple<Integer,Id<TAZ>>, List<Double>> dataMap) {
        String heading = "timeBin,TAZ,avgWait,medianWait,numberOfPickups,avgPoolingDelay,numberOfPooledPickups";
        String fileBaseName = "rideHailWaitingStats";
        String csvFileName = ioController.getIterationFilename(iterationNumber, fileBaseName + ".csv");
        BufferedWriter outWriter = IOUtils.getBufferedWriter(csvFileName);
        try {
            outWriter.write(heading);
            outWriter.newLine();
            dataMap.forEach((k,v) -> {
                DoubleStream valuesAsDouble = v.stream()
                        .mapToDouble(x -> x);
                DoubleSummaryStatistics stats = valuesAsDouble
                        .summaryStatistics();
                String line = k.getFirst() + "," + k.getSecond().toString() + "," + stats.getAverage() + "," +
                        MathUtils.median(v) + "," + stats.getCount() + "," + 0 + "," + 0;
                try {
                    outWriter.write(line);
                    outWriter.newLine();
                } catch (IOException e) {
                    log.error("exception occurred due to ", e);
                }
            });
            outWriter.flush();
            outWriter.close();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    @Override
    public void resetStats() {
        binWaitingTimesMap.clear();
        rideHailWaitingQueue.clear();
    }
}
