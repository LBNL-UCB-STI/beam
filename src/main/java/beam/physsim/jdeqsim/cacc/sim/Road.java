package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

import java.util.HashMap;

public class Road extends org.matsim.core.mobsim.jdeqsim.Road {

    public double CACC;
    private static RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction;
    private HashMap<Vehicle,Double> caccShareEncounteredByVehicle=new HashMap<>();


    public Road(Scheduler scheduler, Link link) {

        super(scheduler, link);
    }



    public static void setRoadCapacityAdjustmentFunction(RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction) {
        Road.roadCapacityAdjustmentFunction = roadCapacityAdjustmentFunction;
    }

    public void updateCACCShareEncounteredByVehicle(Vehicle vehicle) {

        double numCACC = 0;
        for (org.matsim.core.mobsim.jdeqsim.Vehicle veh : carsOnTheRoad) {
            if (vehicle.isCACCVehicle()) {
                numCACC++;
            }
        }

        if (carsOnTheRoad.size()>0 && !vehicle.getOwnerPerson().getId().toString().contains("bus")){
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        // if we would set this to 0, no car would be worse than CACC and have a worse road capacity, which does not make sense
        double caccShare=1.0;
        if (carsOnTheRoad.size()!= 0) {
            caccShare = (numCACC / carsOnTheRoad.size());
        }

        caccShareEncounteredByVehicle.put(vehicle,caccShare);

    }

    @Override
    public void enterRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {

        updateCACCShareEncounteredByVehicle((Vehicle) vehicle);

        double nextAvailableTimeForLeavingStreet = getNextAvailableTimeForLeavingStreet(simTime);

        markCarAsProcessed((Vehicle) vehicle);

        updateEarliestDepartureTimeOfCar(nextAvailableTimeForLeavingStreet);

        if (onlyOneCarRoad()) {
            nextAvailableTimeForLeavingStreet = Math.max(nextAvailableTimeForLeavingStreet,
                    this.timeOfLastLeavingVehicle + (1/roadCapacityAdjustmentFunction.getCapacityWithCACC(link,caccShareEncounteredByVehicle.remove(vehicle))));
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
