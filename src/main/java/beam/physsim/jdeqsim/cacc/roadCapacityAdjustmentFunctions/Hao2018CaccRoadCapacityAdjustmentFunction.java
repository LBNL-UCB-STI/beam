package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions;

import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.RoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import beam.utils.DebugLib;
import beam.utils.FileUtils;
import org.apache.log4j.Logger;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


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
    private StringBuffer capacityStatsCollector = new StringBuffer();

    private double capacityIncreaseSum=0;
    private double percentageCapacityIncreaseSum=0;
    private int currentIterationNumber;
    private int writeInterval;
    private int binSize;
    private OutputDirectoryHierarchy controllerIO;

    private int nonCACCCategoryRoadsTravelled=0;
    private int caccCategoryRoadsTravelled=0;
    private double flowCapacityFactor;
    private Map<Integer,Double> caccEnabledVehiclesTravellingRatio = new HashMap<>();

    public Hao2018CaccRoadCapacityAdjustmentFunction(double caccMinRoadCapacity, double caccMinSpeedMetersPerSec, double flowCapacityFactor, int iterationNumber, OutputDirectoryHierarchy controllerIO, int writeInterval,int binSize){
        this.flowCapacityFactor = flowCapacityFactor;
        log.info("caccMinRoadCapacity: " + caccMinRoadCapacity + ", caccMinSpeedMetersPerSec: " + caccMinSpeedMetersPerSec );
        this.caccMinRoadCapacity = caccMinRoadCapacity;
        this.caccMinSpeedMetersPerSec = caccMinSpeedMetersPerSec;
        this.currentIterationNumber = iterationNumber;
        this.controllerIO = controllerIO;
        this.writeInterval = writeInterval;
        this.binSize = binSize;
    }

    public boolean isCACCCategoryRoad(Link link){
        double initialCapacity=link.getCapacity();
        return initialCapacity>=caccMinRoadCapacity && link.getFreespeed()>=caccMinSpeedMetersPerSec;
    }

    public double getCapacityWithCACCPerSecond(Link link, double fractionCACCOnRoad,double simTime){
        double initialCapacity=link.getCapacity();
        double updatedCapacity=initialCapacity;

        if (isCACCCategoryRoad(link)) {
            caccCategoryRoadsTravelled++;
            updatedCapacity=(2152.777778 * fractionCACCOnRoad * fractionCACCOnRoad * fractionCACCOnRoad - 764.8809524 * fractionCACCOnRoad * fractionCACCOnRoad + 456.1507937 * fractionCACCOnRoad + 1949.047619) / 1949.047619 * initialCapacity;



            if (fractionCACCOnRoad==1){
                numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads++;
            }

            if (fractionCACCOnRoad==0){
                numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads++;
            }

            if (fractionCACCOnRoad>0 && fractionCACCOnRoad<=1.0){
                numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads++;
                capacityIncreaseSum+=updatedCapacity-initialCapacity;
                percentageCapacityIncreaseSum+=(updatedCapacity/initialCapacity-1.0);
            }


            if (updatedCapacity<initialCapacity){
                log.error("updatedCapacity (" + updatedCapacity +") is lower than initialCapacity (" + initialCapacity + ").");
            }

            String dataLine = link.getId().toString() + "," + fractionCACCOnRoad + "," + initialCapacity + "," + updatedCapacity;
            capacityStatsCollector.append(dataLine).append("\n");

            int hourOfSimulation = (int)(simTime / binSize);
            caccEnabledVehiclesTravellingRatio.put(hourOfSimulation, (double) (numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads / caccCategoryRoadsTravelled) *100);

        } else {
            nonCACCCategoryRoadsTravelled++;
        }

        return updatedCapacity /3600;
    }

    public void printStats(){
        log.info("average road capacity increase: " + capacityIncreaseSum/numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);
        log.info("average road capacity increase (%): " + percentageCapacityIncreaseSum/numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads*100.0);
        log.info("number of mixed vehicle type encounters (non-CACC/CACC) on CACC category roads: " + numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);
        log.info("numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads);
        log.info("numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads);
        log.info("caccCategoryRoadsTravelled / nonCACCCategoryRoadsTravelled ratio: " + 1.0 * caccCategoryRoadsTravelled / nonCACCCategoryRoadsTravelled);
        writeCapacityStats(currentIterationNumber,capacityStatsCollector.toString());
        createGraph(currentIterationNumber,caccEnabledVehiclesTravellingRatio);
        reset();
    }

    private void writeCapacityStats(int iterationNumber,String statsData) {
        if (isWriteEnabled(iterationNumber)) {
            String header = "linkId,fractionCACCOnRoad,initialCapacity,updatedCapacity";
            String filePath = controllerIO.getIterationFilename(iterationNumber,"caccCapacityStats.csv.gz");
            FileUtils.writeToFileJava(filePath,Optional.of(header),statsData, Optional.empty());
        }
    }

    private boolean isWriteEnabled(int iterationNumber) {
        return  (writeInterval > 0 && iterationNumber % writeInterval == 0);
    }

    public void createGraph(int iterationNumber , Map<Integer,Double> vehiclesTravellingRatio) {

        int noOfBins = Collections.max(vehiclesTravellingRatio.keySet()) + 1;
        double[][] dataMatrix = new double[100][noOfBins];
        for (int i = 0; i < vehiclesTravellingRatio.size(); i++) {
            dataMatrix[0][i] = vehiclesTravellingRatio.getOrDefault(i,0.0);
        }

        CategoryDataset dataSet = DatasetUtilities.createCategoryDataset("", "", dataMatrix);

        String plotTitle = "CACC vehicles distribution over the day";
        String x_axis = "Time Bin";
        String y_axis = "Percentage of CACC vehicles over CACC roads";
        int width = 1000;
        int height = 600;

        // Setting orientation for the plot
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        // Create the chart
        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        //Save the chart as image
        String graphImageFile = controllerIO.getIterationFilename(iterationNumber,"caccVehiclesDistribution.png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void reset() {
        caccEnabledVehiclesTravellingRatio.clear();
    }

}
