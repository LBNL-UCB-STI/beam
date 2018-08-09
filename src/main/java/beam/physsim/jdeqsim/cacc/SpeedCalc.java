package beam.physsim.jdeqsim.cacc;


import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;


import java.util.HashMap;
import java.util.Set;

import static beam.physsim.jdeqsim.cacc.jdeqsim.JDEQSimulation.isCACCVehicle;

public class SpeedCalc implements LinkEnterEventHandler, PersonArrivalEventHandler, LinkLeaveEventHandler {
    private int linkEnterCount;
    private Scenario scenario;

    public SpeedCalc(Scenario scenario) {
        this.scenario = scenario;


    }


    // TODO: Speed based on % CACC
    // TODO: 5% CACC, Travel time decreased by 2.5%
    // TODO: calculate travel time, Hashmap (key = vehicle ID, value = enter time)

   /* public double percentCACC(Set<String> vehicleIDs) {
        double numCACC = 0;
        double totalVehicles = 100;
        for (Boolean s : isCACCVehicle.values()) {
            if (s) {
                numCACC++;
            }

        }

        return (numCACC / totalVehicles) * 100;
    }*/

    public double percentCACC(Set<String> vehicleIDs) {
        double numCACC = 0;
        for (String vehicleID : vehicleIDs) {
            if (isCACCVehicle.get(vehicleID)) {
                numCACC++;
            }

        }

        return (numCACC / vehicleIDs.size()) * 100;
    }
    public static HashMap<String,Integer> allTravelTimes = new HashMap();

    public HashMap<String , Integer> travelTimes = new HashMap<>();


    @Override
    public void reset(int iteration) {
        // TODO Auto-generated method stub
    }
    //Travel Time from Link 1 to 6
    @Override
    //Enter Link
    public void handleEvent(LinkEnterEvent event) {
        if (Integer.parseInt(event.getLinkId().toString()) == 6) {
            linkEnterCount++;

            double timeEnter = Double.parseDouble(event.getAttributes().get(LinkEnterEvent.ATTRIBUTE_TIME));
            System.out.print("Time Enter " + timeEnter);
            System.out.print(" Vehicle ID " + event.getVehicleId()+ " onto ");
            System.out.println("Link ID " + event.getLinkId());

            travelTimes.put(event.getVehicleId().toString(),(int)timeEnter);



        }
    }



    @Override
    //Leave Link
    public void handleEvent(LinkLeaveEvent event) {
        if (Integer.parseInt(event.getLinkId().toString()) == 6) {

        double timeLeave = Double.parseDouble(event.getAttributes().get(LinkEnterEvent.ATTRIBUTE_TIME));

        System.out.println((int) (timeLeave - travelTimes.get(event.getVehicleId().toString())));
        allTravelTimes.put(event.getVehicleId().toString(),(int) (timeLeave - travelTimes.get(event.getVehicleId().toString())));
        }
    }

    public static double getAvgTravelTime(){
        
    int totalTime = 0;
        for ( int i:allTravelTimes.values() ) {
            totalTime+=i;
        }
      return totalTime/100;
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {


    }



}
