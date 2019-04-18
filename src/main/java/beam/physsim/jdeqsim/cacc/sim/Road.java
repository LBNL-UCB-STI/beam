package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.jdeqsim.DeadlockPreventionMessage;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;

public class Road extends org.matsim.core.mobsim.jdeqsim.Road {

    public double CACC;
    private static RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction;
    private HashMap<Vehicle,Double> caccShareEncounteredByVehicle=new HashMap<>();
    private double speedAdjustmentFactor;


    public Road(Scheduler scheduler, Link link , double speedAdjustmentFactor) {

        super(scheduler, link);
        this.speedAdjustmentFactor = speedAdjustmentFactor;
    }



    public static void setRoadCapacityAdjustmentFunction(RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction) {
        Road.roadCapacityAdjustmentFunction = roadCapacityAdjustmentFunction;
    }

    public void updateCACCShareEncounteredByVehicle(Vehicle vehicle) {

        double numCACC = 0;
        for (org.matsim.core.mobsim.jdeqsim.Vehicle veh : carsOnTheRoad) {
            if (((Vehicle) veh).isCACCVehicle()) {
                numCACC++;
            }
        }

        if (carsOnTheRoad.size()>=2 & vehicle.getOwnerPerson().getId().toString().contains("SF")){
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        if (carsOnTheRoad.size()>=2 & carsOnTheRoad.size()!=numCACC && numCACC!=0){
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        if (carsOnTheRoad.size()>0 && !vehicle.getOwnerPerson().getId().toString().contains("bus")){
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        // if we would set this to 0, no car would be worse than CACC and have a worse road capacity, which does not make sense
        double caccShare=getInitialCACCShare(vehicle);

        if (carsOnTheRoad.size()!= 1) {
            caccShare = (1.0 * numCACC / carsOnTheRoad.size());
        }

        if (caccShare>0 && caccShare<1.0){
            DebugLib.emptyFunctionForSettingBreakPoint();
            //System.out.println("updateCACCShareEncounteredByVehicle - linkId:" + getLink().getId() + ";vehicle:" + vehicle.getOwnerPerson().getId() + ";carsOnTheRoad.size():" + carsOnTheRoad.size() + "numCACC:" + numCACC + ";caccShare:" + caccShare);

        }

        //System.out.println("linkId:" + getLink().getId() + ";vehicle:" + vehicle.getOwnerPerson().getId() + ";carsOnTheRoad.size():" + carsOnTheRoad.size() + "numCACC:" + numCACC + ";caccShare:" + caccShare);
        //System.out.print("carsOnTheRoad: " );


        for (org.matsim.core.mobsim.jdeqsim.Vehicle v: carsOnTheRoad){
            //System.out.print(v.getOwnerPerson().getId() + ", ");
        }


        //System.out.println();
        //System.out.println();

        caccShareEncounteredByVehicle.put(vehicle,caccShare);

    }


    public double getInitialCACCShare(Vehicle vehicle){
        return vehicle.isCACCVehicle()?1.0:0.0;
    }

    @Override
    public void enterRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {

        double nextAvailableTimeForLeavingStreet = getNextAvailableTimeForLeavingStreet(simTime);

        markCarAsProcessed((Vehicle) vehicle);

        updateCACCShareEncounteredByVehicle((Vehicle) vehicle);

        updateEarliestDepartureTimeOfCar(nextAvailableTimeForLeavingStreet);

        if (onlyOneCarRoad()) {



            nextAvailableTimeForLeavingStreet=Math.max(nextAvailableTimeForLeavingStreet,
                    this.timeOfLastLeavingVehicle + getInverseCapacity(vehicle,simTime));
            vehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }

    }




    private double getInverseCapacity(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime){
        double caccShare=getInitialCACCShare((Vehicle) vehicle);

        if (caccShareEncounteredByVehicle.containsKey(vehicle)){
            caccShare=caccShareEncounteredByVehicle.remove(vehicle);
        }

        return (1/roadCapacityAdjustmentFunction.getCapacityWithCACCPerSecond(link,caccShare,simTime)*config.getFlowCapacityFactor());
    }





    private LinkedList<Double> gap_;
    private LinkedList<org.matsim.core.mobsim.jdeqsim.Vehicle> interestedInEnteringRoad_;
    LinkedList<DeadlockPreventionMessage> deadlockPreventionMessages_;
    private double timeOfLastEnteringVehicle_ = Double.MIN_VALUE;
    private double gapTravelTime_ = 0;
    private double inverseInFlowCapacity_ = 0;


    private Object getField(Object obj, String fieldName){
        Field field= null;
        try {
            field = org.matsim.core.mobsim.jdeqsim.Road.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }


    private void preLeaveRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime){
        gap_= (LinkedList<Double>) getField(this , "gap");
        interestedInEnteringRoad_=(LinkedList<org.matsim.core.mobsim.jdeqsim.Vehicle>) getField(this,"interestedInEnteringRoad");
        deadlockPreventionMessages_=(LinkedList<DeadlockPreventionMessage>) getField(this,"deadlockPreventionMessages");
        timeOfLastEnteringVehicle_ =(Double) getField(this,"timeOfLastEnteringVehicle");
        gapTravelTime_ =(Double)getField(this,"gapTravelTime");
        inverseInFlowCapacity_ =(Double)getField(this,"inverseInFlowCapacity");

        assert (this.carsOnTheRoad.getFirst() == vehicle);
        assert (this.interestedInEnteringRoad_.size()==this.deadlockPreventionMessages_.size());

        this.carsOnTheRoad.removeFirst();
        this.earliestDepartureTimeOfCar.removeFirst();
        this.timeOfLastLeavingVehicle = simTime;

        if (this.interestedInEnteringRoad_.size() > 0) {
            org.matsim.core.mobsim.jdeqsim.Vehicle nextVehicle = this.interestedInEnteringRoad_.removeFirst();
            DeadlockPreventionMessage m = this.deadlockPreventionMessages_.removeFirst();
            assert (m.vehicle == nextVehicle);
            this.scheduler.unschedule(m);

            double nextAvailableTimeForEnteringStreet = Math.max(this.timeOfLastEnteringVehicle_
                    + getInverseCapacity(vehicle,simTime), simTime + this.gapTravelTime_);

            this.noOfCarsPromisedToEnterRoad++;

            nextVehicle.scheduleEnterRoadMessage(nextAvailableTimeForEnteringStreet, this);
        } else {
            if (this.gap_ != null) {

                this.gap_.add(simTime + this.gapTravelTime_);

                if (this.carsOnTheRoad.size() == 0) {
                    this.gap_ = null;
                }
            }
        }
    }


    @Override
    public void leaveRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        preLeaveRoad(vehicle,simTime);

        if (this.carsOnTheRoad.size() > 0) {
            org.matsim.core.mobsim.jdeqsim.Vehicle nextVehicle = this.carsOnTheRoad.getFirst();
            double nextAvailableTimeForLeavingStreet = Math.max(this.earliestDepartureTimeOfCar.getFirst(),
                    this.timeOfLastLeavingVehicle + getInverseCapacity(vehicle,simTime));
            nextVehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
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
                / (this.link.getFreespeed(simTime)*speedAdjustmentFactor);
    }

}
