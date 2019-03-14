package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions;

import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import beam.utils.DebugLib;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Link;


/*

CACC regression function derived from (Figure 8, Simulation):

Liu, Hao, et al. "Modeling impacts of Cooperative Adaptive Cruise Control on mixed traffic flow
in multi-lane freeway facilities." Transportation Research Part C: Emerging Technologies 95 (2018): 261-279.

 */

public class Hao2018CaccRoadCapacityAdjustmentFunction implements RoadCapacityAdjustmentFunction {

    private final static Logger log = Logger.getLogger(Hao2018CaccRoadCapacityAdjustmentFunction.class);

    private double caccMinRoadCapacity;
    private double caccMinSpeedMetersPerSec;
    private int numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads=0;
    private int numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads=0;
    private int numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads=0;

    private double capacityIncreaseSum=0;


    private double nonCACCCategoryRoadsTravelled=0;
    private double caccCategoryRoadsTravelled=0;

    public Hao2018CaccRoadCapacityAdjustmentFunction(double caccMinRoadCapacity, double caccMinSpeedMetersPerSec){
        log.info("caccMinRoadCapacity: " + caccMinRoadCapacity + ", caccMinSpeedMetersPerSec: " + caccMinSpeedMetersPerSec );
        this.caccMinRoadCapacity = caccMinRoadCapacity;
        this.caccMinSpeedMetersPerSec = caccMinSpeedMetersPerSec;
    }



    public boolean isCACCCategoryRoad(Link link){
        double initialCapacity=link.getFlowCapacityPerSec();

        return initialCapacity>=caccMinRoadCapacity && link.getFreespeed()>caccMinSpeedMetersPerSec;
    }

    public double getCapacityWithCACC(Link link, double fractionCACCOnRoad){


        double initialCapacity=link.getFlowCapacityPerSec();



        if (isCACCCategoryRoad(link)) {

            if (fractionCACCOnRoad==1){
                numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads++;
            }

            if (fractionCACCOnRoad==0){
                numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads++;
            }






            if (fractionCACCOnRoad>0 && fractionCACCOnRoad<1.0){
                // System.out.println("Hao2018CaccRoadCapacityAdjustmentFunction - linkId:" + link.getId() + ";vehicle:" + ";fractionCACCOnRoad:" + fractionCACCOnRoad + ", initialCapacity" + initialCapacity);
                numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads++;
            }


            nonCACCCategoryRoadsTravelled++;
            double updatedCapacity=(2152.777778 * fractionCACCOnRoad * fractionCACCOnRoad * fractionCACCOnRoad - 764.8809524 * fractionCACCOnRoad * fractionCACCOnRoad + 456.1507937 * fractionCACCOnRoad + 1949.047619) / 1949.047619 * initialCapacity;


            if (updatedCapacity<initialCapacity){
               log.error("updatedCapacity (" + updatedCapacity +") is lower than initialCapacity (" + initialCapacity + ").");
            } else {
                if (fractionCACCOnRoad>0 && fractionCACCOnRoad<=1.0){
                    numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads++;
                    capacityIncreaseSum+=updatedCapacity-initialCapacity;
                }
            }

            return updatedCapacity;

        } else {
           caccCategoryRoadsTravelled++;
           return initialCapacity;
        }

    }

    public void printStats(){
        log.info("number of non-CACC/CACC vehicle type encounters on CACC category roads: " + numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);

        log.info("average road capacity increase: " + capacityIncreaseSum/numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);


        log.info("numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads);
        log.info("numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads);

        log.info("caccCategoryRoadsTravelled: " + caccCategoryRoadsTravelled + "nonCACCCategoryRoadsTravelled: " + nonCACCCategoryRoadsTravelled);
    }
}
