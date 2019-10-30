package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.Id;
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
    private double minimumRoadSpeedInMetersPerSecond=1.3;

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

    public HashMap<org.matsim.core.mobsim.jdeqsim.Vehicle,Double> latestTimeToLeaveRoad = new HashMap<>();

    @Override
    public void enterRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        double nextAvailableTimeForLeavingStreet = getNextAvailableTimeForLeavingStreet(simTime);

        markCarAsProcessed((Vehicle) vehicle);

        updateCACCShareEncounteredByVehicle((Vehicle) vehicle);

        updateEarliestDepartureTimeOfCar(nextAvailableTimeForLeavingStreet);

        //System.out.println("enterRoad:" + link.getId() + "; vehicle:" + vehicle.getOwnerPerson().getId());
        latestTimeToLeaveRoad.put(vehicle,simTime + link.getLength()/minimumRoadSpeedInMetersPerSecond);

        if (onlyOneCarRoad()) {
            double lastTimeLEavingPlusInverseCapacity = timeOfLastLeavingVehicle + getInverseCapacity(vehicle, simTime);
            nextAvailableTimeForLeavingStreet = Math.max(nextAvailableTimeForLeavingStreet, lastTimeLEavingPlusInverseCapacity);
            vehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }
    }

    private double getInverseCapacity(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime){
        double caccShare=getInitialCACCShare((Vehicle) vehicle);

        if (caccShareEncounteredByVehicle.containsKey(vehicle)){
            caccShare=caccShareEncounteredByVehicle.remove(vehicle);
        }

        double capacityWithCACCPerSecond = roadCapacityAdjustmentFunction.getCapacityWithCACCPerSecond(link, caccShare, simTime);
        double flowCapacityFactor = config.getFlowCapacityFactor();
        return (1 / capacityWithCACCPerSecond * flowCapacityFactor);
    }

    private LinkedList<Double> gap_;
    private LinkedList<org.matsim.core.mobsim.jdeqsim.Vehicle> interestedInEnteringRoad_;
    LinkedList<DeadlockPreventionMessage> deadlockPreventionMessages_;
    private double timeOfLastEnteringVehicle_ = Double.MIN_VALUE;
    private double gapTravelTime_ = 0;
    private double inverseInFlowCapacity_ = 0;
    private Long maxNumberOfCarsOnRoad_ = 0L;


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

        //System.out.println("leaveRoad:" + link.getId() + "; vehicle:" + vehicle.getOwnerPerson().getId());
        latestTimeToLeaveRoad.remove(vehicle);

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

    private void setPrivateField(){

    }

    @Override
    public void enterRequest(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {


        gap_= (LinkedList<Double>) getField(this , "gap");
        interestedInEnteringRoad_=(LinkedList<org.matsim.core.mobsim.jdeqsim.Vehicle>) getField(this,"interestedInEnteringRoad");
        deadlockPreventionMessages_=(LinkedList<DeadlockPreventionMessage>) getField(this,"deadlockPreventionMessages");
        timeOfLastEnteringVehicle_ =(Double) getField(this,"timeOfLastEnteringVehicle");
        gapTravelTime_ =(Double)getField(this,"gapTravelTime");
        inverseInFlowCapacity_ =(Double)getField(this,"inverseInFlowCapacity");
        maxNumberOfCarsOnRoad_ =(Long)getField(this,"maxNumberOfCarsOnRoad");

        assert (interestedInEnteringRoad_.size()==deadlockPreventionMessages_.size());

        // is there any space on the road (including promised entries?)
        if (this.carsOnTheRoad.size() + this.noOfCarsPromisedToEnterRoad < maxNumberOfCarsOnRoad_) {
            /*
             * - check, if the gap needs to be considered for entering the road -
             * we can find out, the time since when we have a free road for
             * entrance for sure:
             */

            // the gap queue will only be empty in the beginning
            double arrivalTimeOfGap = Double.MIN_VALUE;
            // if the road has been full recently then find out, when the next
            // gap arrives
            if ((gap_ != null) && (gap_.size() > 0)) {
                arrivalTimeOfGap = gap_.remove();
            }

            this.noOfCarsPromisedToEnterRoad++;
            double nextAvailableTimeForEnteringStreet = Math.max(Math.max(timeOfLastEnteringVehicle_
                    + inverseInFlowCapacity_, simTime), arrivalTimeOfGap);

            timeOfLastEnteringVehicle_ = nextAvailableTimeForEnteringStreet;

            // write private field back to super class
            Field field= null;
            try {
                field = org.matsim.core.mobsim.jdeqsim.Road.class.getDeclaredField("timeOfLastEnteringVehicle");
                field.setAccessible(true);
                field.setDouble(this,(Double) timeOfLastEnteringVehicle_);
            } catch (Exception e) {
                e.printStackTrace();
            }

            vehicle.scheduleEnterRoadMessage(nextAvailableTimeForEnteringStreet, this);
        } else {
            /*
             * - if the road was empty then create a new queue else empty the
             * old queue As long as the gap is null, the road is not full (and
             * there is no reason to keep track of the gaps => see leaveRoad)
             * But when the road gets full once, we need to start keeping track
             * of the gaps Once the road is empty again, gap is reset to null
             * (see leaveRoad).
             *
             * The gap variable in only needed for the situation, where the
             * street has been full recently, but the interestedInEnteringRoad
             * is empty and a new car arrives (or a few). So, if the street is
             * long, it takes time for the gap to come back.
             *
             * As long as interestedInEnteringRoad is not empty, newly generated
             * gaps get used by the new cars (see leaveRoad)
             */
            if (gap_ == null) {
                gap_ = new LinkedList<>();
            } else {
                gap_.clear();
            }

            interestedInEnteringRoad_.add(vehicle);

            /*
             * the first car interested in entering a road has to wait
             * 'stuckTime' the car behind has to wait an additional stuckTime
             * (this logic was adapted to adhere to the C++ implementation)
             */
            double nextStuckTime=0;

            if (deadlockPreventionMessages_.size() > 0) {
                nextStuckTime=deadlockPreventionMessages_.getLast().getMessageArrivalTime() + config.getSqueezeTime();

            } else {
                nextStuckTime=simTime
                        + config.getSqueezeTime();
            }


            if (!Road.getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.containsKey(vehicle)){
                Road.getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.put(vehicle,simTime + link.getLength()/minimumRoadSpeedInMetersPerSecond);
            } else {
                System.out.print("");
            }



            //System.out.print("enterRequest:" + link.getId() + "; vehicle:" + vehicle.getOwnerPerson().getId());
            //System.out.println("; vehicle.getCurrentLinkId():" + vehicle.getCurrentLinkId());

            double minTimeForNextDeadlockPreventionMessageTime=0;

            if (deadlockPreventionMessages_.size() > 0) minTimeForNextDeadlockPreventionMessageTime=deadlockPreventionMessages_.getLast().getMessageArrivalTime()+0.0000000001; // ensures that deadlock prevention messages have increasing time stamps - this is assumped by original implementation around this



            double timeToLeaveRoad=Math.max(Math.min(Road.getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.get(vehicle),nextStuckTime),minTimeForNextDeadlockPreventionMessageTime);

            deadlockPreventionMessages_.add(vehicle.scheduleDeadlockPreventionMessage(timeToLeaveRoad, this));

            assert (interestedInEnteringRoad_.size()==deadlockPreventionMessages_.size()) :interestedInEnteringRoad_.size() + " - " + deadlockPreventionMessages_.size();
        }
    }

    public static Road getRoad(Id<Link> linkId) {
        return (Road) getAllRoads().get(linkId);
    }



}