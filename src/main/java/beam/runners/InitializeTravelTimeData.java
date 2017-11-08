package beam.runners;

import beam.sim.traveltime.ExogenousTravelTime;

/**
 * BEAM
 */
public class InitializeTravelTimeData {
    // Generate and serialize the travel time by link data
    public static void main(String[] args) {

        System.out.println("Loading travel times from validation file: "+args[0]);
        String filepathToValidationData = args[0];

        ExogenousTravelTime travelTime = ExogenousTravelTime.LoadTravelTimeFromValidationData(filepathToValidationData,true);

        System.out.println("Serializing to file: " + args[1]);
        travelTime.serializeTravelTimeData(args[1]);
    }

}
