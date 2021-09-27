package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.AdditionalLinkTravelTimeCalculationFunction;
import beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions.EmptyRoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions.RoadCapacityAdjustmentFunction;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.jdeqsim.DeadlockPreventionMessage;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Optional;

public class Road extends org.matsim.core.mobsim.jdeqsim.Road {

    private static final double INCREASE_TIMESTAMP = 0.0000000001D;
    private static RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction = new EmptyRoadCapacityAdjustmentFunction();

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static Optional<AdditionalLinkTravelTimeCalculationFunction> additionalLinkTravelTimeCalculationFunction = Optional.empty();

    private final HashMap<Vehicle, Double> caccShareEncounteredByVehicle = new HashMap<>();
    private final double speedAdjustmentFactor;
    private final double minimumRoadSpeedInMetersPerSecond;
    private final HashMap<Id<Link>, org.matsim.core.mobsim.jdeqsim.Road> allRoads;

    public Road(Scheduler scheduler, Link link, double speedAdjustmentFactor, double minimumRoadSpeedInMetersPerSecond,
                JDEQSimConfigGroup config, HashMap<Id<Link>, org.matsim.core.mobsim.jdeqsim.Road> allRoads) {

        super(scheduler, link, config);
        this.speedAdjustmentFactor = speedAdjustmentFactor;
        this.minimumRoadSpeedInMetersPerSecond = minimumRoadSpeedInMetersPerSecond;
        this.allRoads = allRoads;
    }

    public static void setRoadCapacityAdjustmentFunction(RoadCapacityAdjustmentFunction roadCapacityAdjustmentFunction) {
        Road.roadCapacityAdjustmentFunction = roadCapacityAdjustmentFunction;
    }

    public static void setAdditionalLinkTravelTimeCalculationFunction(AdditionalLinkTravelTimeCalculationFunction additionalLinkTravelTimeCalculationFunction) {
        Road.additionalLinkTravelTimeCalculationFunction = Optional.of(additionalLinkTravelTimeCalculationFunction);
    }

    public void updateCACCShareEncounteredByVehicle(Vehicle vehicle) {
        double numCACC = 0;
        for (org.matsim.core.mobsim.jdeqsim.Vehicle veh : carsOnTheRoad) {
            if (((Vehicle) veh).isCACCVehicle()) {
                numCACC++;
            }
        }

        // if we would set this to 0, no car would be worse than CACC and have a worse road capacity, which does not make sense
        double caccShare = getInitialCACCShare(vehicle);

        if (carsOnTheRoad.size() != 1) {
            caccShare = (numCACC / carsOnTheRoad.size());
        }

        caccShareEncounteredByVehicle.put(vehicle, caccShare);
    }

    public double getInitialCACCShare(Vehicle vehicle) {
        return vehicle.isCACCVehicle() ? 1.0 : 0.0;
    }

    public final HashMap<org.matsim.core.mobsim.jdeqsim.Vehicle, Double> latestTimeToLeaveRoad = new HashMap<>();

    @Override
    public void enterRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        double nextAvailableTimeForLeavingStreet = getNextAvailableTimeForLeavingStreet(simTime);

        markCarAsProcessed((Vehicle) vehicle);

        updateCACCShareEncounteredByVehicle((Vehicle) vehicle);

        updateEarliestDepartureTimeOfCar(nextAvailableTimeForLeavingStreet);

        //System.out.println("enterRoad:" + link.getId() + "; vehicle:" + vehicle.getOwnerPerson().getId());
        latestTimeToLeaveRoad.put(vehicle, simTime + link.getLength() / minimumRoadSpeedInMetersPerSecond);

        if (this.carsOnTheRoad.size() == 1) {
            double lastTimeLEavingPlusInverseCapacity = timeOfLastLeavingVehicle + getInverseCapacity(vehicle, simTime);
            nextAvailableTimeForLeavingStreet = Math.max(nextAvailableTimeForLeavingStreet, lastTimeLEavingPlusInverseCapacity);

            nextAvailableTimeForLeavingStreet += getAdditionalTravelTime(simTime);

            vehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }
    }

    private double getAdditionalTravelTime(double simulationTime) {
        return additionalLinkTravelTimeCalculationFunction
                .map(func -> {

                    return func.getAdditionalLinkTravelTime(link, simulationTime);
                })
                .orElse(0.0);
    }

    private double getInverseCapacity(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        double caccShare = getInitialCACCShare((Vehicle) vehicle);

        if (caccShareEncounteredByVehicle.containsKey(vehicle)) {
            caccShare = caccShareEncounteredByVehicle.remove(vehicle);
        }

        double capacityWithCACCPerSecond = roadCapacityAdjustmentFunction.getCapacityWithCACCPerSecond(link, caccShare, simTime);
        double flowCapacityFactor = config.getFlowCapacityFactor();

        return 1 / (capacityWithCACCPerSecond * flowCapacityFactor);
    }

    @Override
    public void processIfInterestedInEnteringRoadTrue(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        org.matsim.core.mobsim.jdeqsim.Vehicle nextVehicle = getInterestedInEnteringRoad().removeFirst();
        DeadlockPreventionMessage m = getDeadlockPreventionMessages().removeFirst();
        assert (m.vehicle == nextVehicle);
        this.scheduler.unschedule(m);

        double nextAvailableTimeForEnteringStreet = Math.max(this.getTimeOfLastEnteringVehicle()
                + getInverseCapacity(vehicle, simTime), simTime + this.getGapTravelTime());

        nextAvailableTimeForEnteringStreet += getAdditionalTravelTime(simTime);

        this.noOfCarsPromisedToEnterRoad++;

        nextVehicle.scheduleEnterRoadMessage(nextAvailableTimeForEnteringStreet, this);
        latestTimeToLeaveRoad.remove(vehicle);
    }

    @Override
    public void leaveRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        assert (this.carsOnTheRoad.getFirst() == vehicle);
        assert (this.getInterestedInEnteringRoad().size() == this.getDeadlockPreventionMessages().size());

        this.carsOnTheRoad.removeFirst();
        this.earliestDepartureTimeOfCar.removeFirst();
        this.timeOfLastLeavingVehicle = simTime;

        if (this.getInterestedInEnteringRoad().size() > 0) {
            processIfInterestedInEnteringRoadTrue(vehicle, simTime);
        } else {
            processIfInterestedInEnteringRoadFalse(vehicle, simTime);
        }

        if (this.carsOnTheRoad.size() > 0) {
            org.matsim.core.mobsim.jdeqsim.Vehicle nextVehicle = this.carsOnTheRoad.getFirst();
            double nextAvailableTimeForLeavingStreet = Math.max(this.earliestDepartureTimeOfCar.getFirst(),
                    this.timeOfLastLeavingVehicle + getInverseCapacity(vehicle, simTime));

            nextAvailableTimeForLeavingStreet += getAdditionalTravelTime(simTime);

            nextVehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }
    }

    @Override
    public void processIfInterestedInEnteringRoadFalse(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        super.processIfInterestedInEnteringRoadFalse(vehicle, simTime);
        latestTimeToLeaveRoad.remove(vehicle);
    }

    private void updateEarliestDepartureTimeOfCar(double nextAvailableTimeForLeavingStreet) {
        this.earliestDepartureTimeOfCar.add(nextAvailableTimeForLeavingStreet);
    }

    private void markCarAsProcessed(Vehicle vehicle) {
        this.noOfCarsPromisedToEnterRoad--;
        this.carsOnTheRoad.add(vehicle);
    }

    private double getNextAvailableTimeForLeavingStreet(double simTime) {
        return simTime + this.link.getLength()
                / (this.link.getFreespeed(simTime) * speedAdjustmentFactor);
    }

    @Override
    public void procsessFilledRoad(org.matsim.core.mobsim.jdeqsim.Vehicle vehicle, double simTime) {
        LinkedList<Double> gap = getGap();
        if (gap == null) {
            setGap(new LinkedList<>());
        } else {
            gap.clear();
        }

        getInterestedInEnteringRoad().add(vehicle);

        /*
         * the first car interested in entering a road has to wait
         * 'stuckTime' the car behind has to wait an additional stuckTime
         * (this logic was adapted to adhere to the C++ implementation)
         */
        double nextStuckTime;
        if (getDeadlockPreventionMessages().size() > 0) {
            nextStuckTime = getDeadlockPreventionMessages().getLast().getMessageArrivalTime() + config.getSqueezeTime();
        } else {
            nextStuckTime = simTime + config.getSqueezeTime();
        }

        if (!getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.containsKey(vehicle)) {
            getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.put(vehicle, simTime + link.getLength() / minimumRoadSpeedInMetersPerSecond);
        }

        double minTimeForNextDeadlockPreventionMessageTime = 0;

        if (getDeadlockPreventionMessages().size() > 0)
            // ensures that deadlock prevention messages have increasing time stamps - this is assumped by original implementation around this
            minTimeForNextDeadlockPreventionMessageTime =
                    getDeadlockPreventionMessages().getLast().getMessageArrivalTime() + INCREASE_TIMESTAMP;

        double latestTimeToLeaveRoad = getRoad(vehicle.getCurrentLinkId()).latestTimeToLeaveRoad.get(vehicle);
        double timeToLeaveRoad = Math.max(Math.min(latestTimeToLeaveRoad, nextStuckTime), minTimeForNextDeadlockPreventionMessageTime);

        getDeadlockPreventionMessages().add(vehicle.scheduleDeadlockPreventionMessage(timeToLeaveRoad, this));

        assert (getInterestedInEnteringRoad().size() == getDeadlockPreventionMessages().size()) : getInterestedInEnteringRoad().size() + " - " + getDeadlockPreventionMessages().size();
    }

    public Road getRoad(Id<Link> linkId) {
        return (Road) allRoads.get(linkId);
    }
}