package beam.playground.jdeqsim_with_cacc;


import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.vehicles.Vehicle;
import scala.Int;


import java.util.HashMap;

import static beam.playground.jdeqsim_with_cacc.jdeqsim.JDEQSimulation.isCACCVehicle;

public class SpeedCalc implements LinkEnterEventHandler, PersonArrivalEventHandler, LinkLeaveEventHandler {
    private int linkEnterCount;
    private Scenario scenario;

    public SpeedCalc(Scenario scenario) {
        this.scenario = scenario;


    }


    // TODO: Speed based on % CACC
    // TODO: 5% CACC, Travel time decreased by 2.5%
    // TODO: calculate travel time, Hashmap (key = vehicle ID, value = enter time)

    public double percentCACC() {
        double numCACC = 0;
        double totalVehicles = 100;
        for (Boolean s : isCACCVehicle.values()) {
            if (s) {
                numCACC++;
            }

        }

        return (numCACC / totalVehicles) * 100;
    }

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
        System.out.print("Time Leave " + timeLeave);
        System.out.print(" Vehicle ID " + event.getVehicleId()+ " from ");
        System.out.print("Link ID " + event.getLinkId());

        System.out.println(", Travel Time " + (timeLeave - travelTimes.get(event.getVehicleId().toString())));
        System.out.println(percentCACC()+"%");



        }
    }


    @Override
    public void handleEvent(PersonArrivalEvent event) {
            //System.out.print(event.getPersonId() + " has arrived at ");
            //System.out.println("Link " + event.getLinkId().toString());
            //provides the link length
            //System.out.println(scenario.getNetwork().getLinks().get(event.getLinkId()));





    }



}
