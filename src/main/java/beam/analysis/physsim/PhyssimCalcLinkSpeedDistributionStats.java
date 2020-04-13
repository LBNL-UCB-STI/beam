package beam.analysis.physsim;

import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.OutputDataDescription;
import beam.sim.config.BeamConfig;
import beam.utils.OutputDataDescriptor;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtilities;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.utils.misc.Time;

import java.awt.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Bhavya Latha Bandaru.
 * This class computes the distribution of free flow speed (in both m/s and %) over the network.
 */
public class PhyssimCalcLinkSpeedDistributionStats {

    private static int noOfBins = 24;
    private BeamConfig beamConfig;
    private Network network;
    private OutputDirectoryHierarchy outputDirectoryHierarchy;
    static String outputAsSpeedUnitFileName = "physsimFreeFlowSpeedDistribution";
    private static String outputAsPercentageFileName = "physsimFreeFlowSpeedDistributionAsPercentage";

    public PhyssimCalcLinkSpeedDistributionStats(Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, BeamConfig beamConfig) {
        this.network = network;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.beamConfig = beamConfig;

        // If not test mode pick up bin count from the beam configuration.
        if (isNotTestMode()) {
            Double endTime = Time.parseTime(beamConfig.matsim().modules().qsim().endTime());
            Double noOfTimeBins = endTime / this.beamConfig.beam().physsim().linkStatsBinSize();
            noOfTimeBins = Math.floor(noOfTimeBins);
            noOfBins = noOfTimeBins.intValue() + 1;
        }
    }

    /**
     * Iteration stop notification event listener
     * @param iteration the count of the current iteration
     */
    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        //generate the graph input for the free flow speed distribution
        Map<Integer, Integer> processedSpeedDistributionData = generateInputDataForFreeFlowSpeedGraph(noOfBins,this.network);
        //generate  data matrix for the free flow speed distribution
        double[][] speedDataMatrix = buildDataSetFromSpeedData(processedSpeedDistributionData);
        //generate the graph input for the link efficiencies
        Map<Double, Integer> processedSpeedDistributionAsPercentageData = generateInputDataForLinkEfficiencies(travelTime);
        //generate category data set for free flow speed distribution
        CategoryDataset dataSetForSpeed = DatasetUtilities.createCategoryDataset("Free Speed", "", speedDataMatrix);
        //generate the category data set for link efficiencies
        CategoryDataset dataSetForSpeedAsPercentage = generateLinkEfficienciesDataSet(processedSpeedDistributionAsPercentageData);
        if (this.outputDirectoryHierarchy != null) {
            //If not running in test mode , write output to a csv file
            if (isNotTestMode()) {
                //write data outputs to CSV
                this.writeCSV(speedDataMatrix,outputDirectoryHierarchy.getIterationFilename(iteration, outputAsSpeedUnitFileName+".csv"),"freeSpeedInMetersPerSecond");
                this.writeCSV(processedSpeedDistributionAsPercentageData,outputDirectoryHierarchy.getIterationFilename(iteration, outputAsPercentageFileName+".csv"),"linkEfficiencyInPercentage");
            }
            //generate the required charts - frequency over speed (as m/s)
            if(beamConfig.beam().outputs().writeGraphs()) {
                generateSpeedDistributionBarChart(dataSetForSpeed, iteration);
                generateSpeedDistributionAsPercentageChart(dataSetForSpeedAsPercentage,iteration);
            }
        }
    }

    private CategoryDataset generateLinkEfficienciesDataSet(Map<Double, Integer> generatedDataMap) {
        final DefaultCategoryDataset dataSet = new DefaultCategoryDataset();
        try {
            Map<Integer, Integer> converterMap = new HashMap<>();
            generatedDataMap.forEach((k, v) -> {
                int category = (int) Math.round(k) / 10;
                category = ((category >= 0 && category != 10) ? (category + 1) : category) * 10;
                Integer value = converterMap.getOrDefault(category, 0);
                converterMap.put(category, value + v);
            });
            IntStream.rangeClosed(1,10).forEach(i -> dataSet.addValue(converterMap.getOrDefault(i*10,0),"percentage",String.valueOf(i*10)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return dataSet;
    }

    /**
     * Helper method that writes the final data to a CSV file
     * @param dataMatrix the input data required to generate the charts
     * @param outputFilePath path to the CSV file
     * @param heading header string for the CSV file
     */
    private void writeCSV(double[][] dataMatrix,String outputFilePath,String heading) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath));
            String completeHeading = heading + ",numberOfLinks\n";
            bw.write(completeHeading);
            double[] data = dataMatrix[0];
            IntStream.range(0,data.length)
                    .forEach( i -> {
                        try {
                            bw.write(i + "," + data[i] + "\n");
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper method that writes the final data to a CSV file
     * @param dataMap the input data required to generate the charts
     * @param outputFilePath path to the CSV file
     * @param heading header string for the CSV file
     */
    private void writeCSV(Map<Double, Integer> dataMap,String outputFilePath,String heading) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath));
            String completeHeading = heading + ",linkEfficiencyRounded,numberOfLinks\n";
            bw.write(completeHeading);
            dataMap.forEach((k,v) -> {
                try {
                    bw.write( k + "," + (int)Math.round(k) + "," + v + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // A helper method to test if the application is running in test mode or not
    private boolean isNotTestMode() {
        return beamConfig != null;
    }

    /**
     * Generates input data used to generate free flow speed distribution chart
     * @return input generated data as map ( speed in m/s -> frequency )
     */
    public Map<Integer, Integer> generateInputDataForFreeFlowSpeedGraph(int binsCount,Network network) {
        Map<Integer, Integer> freeFlowSpeedFrequencies = new HashMap<>();
        Stream.iterate(0,x -> x)
                .limit(binsCount)
                .forEach(bin -> network.getLinks().values()
                        .stream()
                        .map(link -> link.getFreespeed(bin * 3600))
                        .map(fs -> (int) Math.round(fs))
                        .forEach(freeSpeed -> {
                            Integer frequencyCount = freeFlowSpeedFrequencies.getOrDefault(freeSpeed,0);
                            freeFlowSpeedFrequencies.put(freeSpeed,frequencyCount+1);
                        }));
        return freeFlowSpeedFrequencies;
    }

    /**
     * Generates input data used to generate frequencies of link efficiencies
     * @return input generated data as map ( speed in m/s -> frequency )
     */
    private Map<Double, Integer> generateInputDataForLinkEfficiencies(TravelTime travelTime) {
        int binSize = 3600;
        Map<Double, Integer> frequencyOfEfficiencies = new HashMap<>();
        //for each bin
        for (int idx = 0; idx < noOfBins; idx++) {
            //for each link
            for (Link link : this.network.getLinks().values()) {
                double freeSpeed = link.getFreespeed(idx * binSize);
                double linkLength = link.getLength();
                double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);
                double averageSpeed = linkLength / averageTime;
                //calculate the average speed of the link
                double averageSpeedToFreeSpeedRatio = averageSpeed / freeSpeed;
                Integer frequencyCount1 = frequencyOfEfficiencies.getOrDefault(averageSpeedToFreeSpeedRatio*100,0);
                frequencyOfEfficiencies.put(averageSpeedToFreeSpeedRatio*100,frequencyCount1+1);
            }
        }
        return frequencyOfEfficiencies;
    }

    /**
     * Generate a 2d data matrix , used to generate category data set for stacked bar chart
     * @param generatedDataMap input data generated as map
     * @return 2d data matrix
     */
    private double[][] buildDataSetFromSpeedData(Map<Integer, Integer> generatedDataMap) {
        Stream<Integer> keys = generatedDataMap.keySet()
                .stream();
        Integer max = keys.max(Comparator.comparing(Integer::valueOf)).orElse(0);
        double[][] dataMatrix = new double[1][max+1];
        for (int i = 1; i <= max; i++) {
            dataMatrix[0][i-1] = generatedDataMap.getOrDefault(i,0);
        }
        return dataMatrix;
    }

    /**
     * Generates a free flow speed (as m/s) distribution stacked bar chart
     * @param dataSet the input data set for the chart
     * @param iterationNumber The number of current iteration
     */
    private void generateSpeedDistributionBarChart(CategoryDataset dataSet, int iterationNumber) {
        // Settings legend and title for the plot
        String plotTitle = "Free Flow Speed Distribution";
        String x_axis = "Free Speed (m/s)";
        String y_axis = "Frequency";
        int width = 1000;
        int height = 600;

        // Setting orientation for the plot
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        // Create the chart
        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, outputAsSpeedUnitFileName+".png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Generates a free flow speed (as %) distribution line chart
     * @param dataSet the input data set for the chart
     * @param iterationNumber The number of current iteration
     */
    private void generateSpeedDistributionAsPercentageChart(CategoryDataset dataSet, int iterationNumber) {
        // Settings legend and title for the plot
        String plotTitle = "Free Flow Speed Distribution (as percentage)";
        String x_axis = "Free Speed (%)";
        String y_axis = "Frequency";
        int width = 1000;
        int height = 800;
        // Setting orientation for the plot
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        // Create the chart
        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, outputAsPercentageFileName+".png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
