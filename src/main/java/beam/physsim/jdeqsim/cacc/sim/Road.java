package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.travelTimeFunctions.TravelTimeFunction;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.jdeqsim.Scheduler;
import org.matsim.core.mobsim.jdeqsim.Vehicle;
import static beam.physsim.jdeqsim.cacc.sim.JDEQSimulation.isCACCVehicle;

public class Road extends org.matsim.core.mobsim.jdeqsim.Road {

    //TODO: where is output, test outputs for change in travel time
    public double CACC;
    private static RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction;


    public Road(Scheduler scheduler, Link link) {

        super(scheduler, link);
    }



    public static void setRoadCapacityAdjustmentFunction(RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction) {
        Road.roadCapacityAdjustmentFunction = roadCapacityAdjustmentFunction;
    }

    public double getShareCACC() {

        double numCACC = 0;
        for (org.matsim.core.mobsim.jdeqsim.Vehicle vehicle : carsOnTheRoad) {
            if (isCACCVehicle.containsKey(vehicle.getOwnerPerson().getId().toString()) && isCACCVehicle.get(vehicle.getOwnerPerson().getId().toString())) {
                numCACC++;
            }
        }

        if (carsOnTheRoad.size() == 0) return 0;
        return (numCACC / carsOnTheRoad.size());
    }

    @Override
    public void enterRoad(Vehicle vehicle, double simTime) {

        double caccShare = getShareCACC();

        double nextAvailableTimeForLeavingStreet = getNextAvailableTimeForLeavingStreet(simTime);

        markCarAsProcessed(vehicle);

        updateEarliestDepartureTimeOfCar(nextAvailableTimeForLeavingStreet);

        if (onlyOneCarRoad()) {
            nextAvailableTimeForLeavingStreet = Math.max(nextAvailableTimeForLeavingStreet,
                    this.timeOfLastLeavingVehicle + (1/roadCapacityAdjustmentFunction.getCapacityWithCACC(link,caccShare)));
            vehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }

    }

    private boolean onlyOneCarRoad() {
        return this.carsOnTheRoad.size() == 1;
    }


    private void updateEarliestDepartureTimeOfCar(double nextAvailableTimeForLeavingStreet){
        this.earliestDepartureTimeOfCar.add(nextAvailableTimeForLeavingStreet);
    }

    private void markCarAsProcessed(Vehicle vehicle){
        this.noOfCarsPromisedToEnterRoad--;
        this.carsOnTheRoad.add(vehicle);
    }


    private double  getNextAvailableTimeForLeavingStreet(double simTime){
        return simTime + this.link.getLength()
                / this.link.getFreespeed(simTime);
    }



}
