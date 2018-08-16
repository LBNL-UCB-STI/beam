package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.travelTimeFunctions.TravelTimeFunction;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.jdeqsim.Scheduler;
import org.matsim.core.mobsim.jdeqsim.Vehicle;
import static beam.physsim.jdeqsim.cacc.sim.JDEQSimulation.isCACCVehicle;

public class Road extends org.matsim.core.mobsim.jdeqsim.Road {

    //TODO: where is output, test outputs for change in travel time
    public double CACC;
    private static TravelTimeFunction travelTimeFunction;

    public Double caccShare = null;

    public Road(Scheduler scheduler, Link link) {

        super(scheduler, link);
    }

    public Road(Scheduler scheduler, Link link, Double caccShare){
        this(scheduler, link);
        this.caccShare = caccShare;
    }

    public static void setTravelTimeFunction(TravelTimeFunction travelTimeFunction) {
        Road.travelTimeFunction = travelTimeFunction;
    }

    //TODO: Plot share CACC vs Travel Times, travel time decreases as number of CACC increases
    //TODO: 100% equal half travel time
    //TODO: improve code structure, Adding tests and refactoring, test events
    //


    public double getShareCACC() {

        if(caccShare == null){
            double numCACC = 0;
            for (org.matsim.core.mobsim.jdeqsim.Vehicle vehicle : carsOnTheRoad) {
                if (isCACCVehicle.get(vehicle.getOwnerPerson().getId().toString())) {
                    numCACC++;
                }
            }
            return (numCACC / carsOnTheRoad.size());
        }else{
            return caccShare;
        }
    }

    @Override
    public void enterRoad(Vehicle vehicle, double simTime) {

        double caccShare = getShareCACC();

//        System.out.println("Speed: " + ((this.link.getLength()) / this.getLink().getFreespeed(simTime)));
        System.out.println("Cacc Share -> " + caccShare);

        this.noOfCarsPromisedToEnterRoad--;
        this.carsOnTheRoad.add(vehicle);

        double nextAvailableTimeForLeavingStreet = simTime + travelTimeFunction.calcTravelTime(link, caccShare);
        this.earliestDepartureTimeOfCar.add(nextAvailableTimeForLeavingStreet);

        if (this.carsOnTheRoad.size() == 1) {
            nextAvailableTimeForLeavingStreet = Math.max(nextAvailableTimeForLeavingStreet,
                    this.timeOfLastLeavingVehicle + this.inverseOutFlowCapacity);
            vehicle.scheduleEndRoadMessage(nextAvailableTimeForLeavingStreet, this);
        }
    }
}
