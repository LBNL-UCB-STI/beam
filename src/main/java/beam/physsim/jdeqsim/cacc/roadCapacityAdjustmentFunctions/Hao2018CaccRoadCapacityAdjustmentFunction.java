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
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
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
    private Map<Integer,Double> caccEnabledVehiclesOverCaccRoads = new HashMap<>();
    private Map<Integer,Double> nonCaccEnabledVehiclesOverCaccRoads = new HashMap<>();
    private Map<Double,Double> caccCapacityIncrease = new HashMap<>();

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

            double caccVehiclesRatio = ((double)numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads / (double)caccCategoryRoadsTravelled);
            caccEnabledVehiclesOverCaccRoads.put(hourOfSimulation,caccVehiclesRatio * 100.0);

            double nonCaccVehiclesRatio = ((double)numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads / (double)caccCategoryRoadsTravelled);
            nonCaccEnabledVehiclesOverCaccRoads.put(hourOfSimulation,nonCaccVehiclesRatio * 100.0);

            double capacityIncrease = (updatedCapacity/initialCapacity)-1.0;
            caccCapacityIncrease.put(fractionCACCOnRoad * 100.0,capacityIncrease * 100.0);

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
        createCaccVehiclesDistributionGraph(currentIterationNumber,caccEnabledVehiclesOverCaccRoads);
        createNonCaccVehiclesDistributionGraph(currentIterationNumber,nonCaccEnabledVehiclesOverCaccRoads);
        createCapacityIncreaseGraph(currentIterationNumber,caccCapacityIncrease);
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

    private void createCaccVehiclesDistributionGraph(int iterationNumber, Map<Integer, Double> caccEnabledVehiclesOverCaccRoads) {

        int noOfBins = Collections.max(caccEnabledVehiclesOverCaccRoads.keySet()) + 1;
        double[][] dataMatrix = new double[100][noOfBins];
        for (int i = 0; i < caccEnabledVehiclesOverCaccRoads.size(); i++) {
            dataMatrix[0][i] = caccEnabledVehiclesOverCaccRoads.getOrDefault(i,0.0);
        }

        CategoryDataset dataSet = DatasetUtilities.createCategoryDataset("", "", dataMatrix);

        String plotTitle = "CACC vehicles distribution over the day";
        String x_axis = "Hour";
        String y_axis = "CACC vehicles over CACC roads (%)";
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

    private void createNonCaccVehiclesDistributionGraph(int iterationNumber, Map<Integer, Double> nonCaccEnabledVehiclesOverCaccRoads) {

        int noOfBins = Collections.max(nonCaccEnabledVehiclesOverCaccRoads.keySet()) + 1;
        double[][] dataMatrix = new double[100][noOfBins];
        for (int i = 0; i < nonCaccEnabledVehiclesOverCaccRoads.size(); i++) {
            dataMatrix[0][i] = nonCaccEnabledVehiclesOverCaccRoads.getOrDefault(i,0.0);
        }

        CategoryDataset dataSet = DatasetUtilities.createCategoryDataset("", "", dataMatrix);

        String plotTitle = "Non CACC vehicles distribution over the day";
        String x_axis = "Hour";
        String y_axis = "Non CACC vehicles over CACC roads (%)";
        int width = 1000;
        int height = 600;

        // Setting orientation for the plot
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        // Create the chart
        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        //Save the chart as image
        String graphImageFile = controllerIO.getIterationFilename(iterationNumber,"nonCaccVehiclesDistribution.png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createCapacityIncreaseGraph(int iterationNumber, Map<Double, Double> caccCapacityIncrease) {

        String plotTitle = "CACC - Road Capacity Increase";
        String x_axis = "CACC on Road (%)";
        String y_axis = "Road Capacity Increase (%)";
        int width = 1000;
        int height = 600;

        XYSeriesCollection dataset = new XYSeriesCollection();
        XYSeries series = new XYSeries("cacc");
        caccCapacityIncrease.forEach(series::add);
        dataset.addSeries(series);

        JFreeChart chart = ChartFactory.createScatterPlot(
                plotTitle,
                x_axis, y_axis, dataset);

        String graphImageFile = controllerIO.getIterationFilename(iterationNumber,"caccRoadCapacityIncrease.png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void reset() {
        caccEnabledVehiclesOverCaccRoads.clear();
    }

}
