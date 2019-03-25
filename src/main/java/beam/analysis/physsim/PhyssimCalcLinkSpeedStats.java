package beam.analysis.physsim;

import beam.sim.config.BeamConfig;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author Bhavya Latha Bandaru.
 * This class computes the percentage of average speed over free speed for the network within a day.
 */
public class PhyssimCalcLinkSpeedStats {

    private static final List<Color> colors = new ArrayList<>();
    private static int noOfBins = 24;
    private static int binSize = 3600;

    // Static initialization of colors
    static {
        colors.add(Color.GREEN);
        colors.add(Color.BLUE);
    }

    private BeamConfig beamConfig;
    private Network network;
    private OutputDirectoryHierarchy outputDirectoryHierarchy;
    static String outputFileName = "physsimLinkAverageSpeedPercentage";

    //Public constructor for the PhyssimCalcLinkSpeedStats class
    public PhyssimCalcLinkSpeedStats(Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, BeamConfig beamConfig) {
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

    // implement the iteration start notification class
    public void notifyIterationEnds(int iteration, TravelTimeCalculator travelTimeCalculator) {
        Map<Integer, Double> processedData = generateInputDataForGraph(travelTimeCalculator);
        CategoryDataset dataSet = generateGraphCategoryDataSet(processedData);
        if (this.outputDirectoryHierarchy != null) {
            //If not running in test mode , write output to a csv file
            if (isNotTestMode()) {
                this.writeCSV(processedData, outputDirectoryHierarchy.getIterationFilename(iteration, outputFileName + ".csv"));
            }
            //generate the requiredGraph
            if (beamConfig.beam().outputs().writeGraphs()) {
                generateAverageLinkSpeedGraph(dataSet, iteration);
            }
        }
    }

    // helper method to write output to a csv file
    private void writeCSV(Map<Integer, Double> processedData, String path) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(path));
            String heading = "Bin,AverageLinkSpeed\n";
            bw.write(heading);
            for (int i = 0; i < processedData.size(); i++) {
                String line = i + "," + processedData.get(i) + "\n";
                bw.write(line);
            }
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // A helper method to test if the application is running in test mode or not
    private boolean isNotTestMode() {
        return beamConfig != null;
    }

    // generate the data required as input to generate a graph
    private Map<Integer, Double> generateInputDataForGraph(TravelTimeCalculator travelTimeCalculator) {
        TravelTime travelTime = travelTimeCalculator.getLinkTravelTimes();

        return IntStream.range(0, noOfBins).parallel().boxed()
                .collect(Collectors.toMap(Function.identity(),
                        idx -> calcLinkAvgSpeedPercentage(travelTime, idx)));
    }

    private double calcLinkAvgSpeedPercentage(TravelTime travelTime, int idx) {
        List<Double> avgSpeeds = this.network.getLinks().values().parallelStream()
                .filter(link -> IntStream.range(0, noOfBins).parallel() // filter links with average speed >= freeSpeed
                        .anyMatch(i -> calcSpeedRatio(i, link, travelTime) >= 1))
                .map(link -> calcSpeedRatio(idx, link, travelTime))
                .collect(Collectors.toList());
        return (avgSpeeds.stream().mapToDouble(Double::doubleValue).sum() / avgSpeeds.size()) * 100;
    }

    private double calcSpeedRatio(int idx, Link link, TravelTime travelTime) {

        double freeSpeed = link.getFreespeed(idx * binSize);
        double linkLength = link.getLength();
        double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);
        double averageSpeed = linkLength / averageTime;
        return averageSpeed / freeSpeed;
    }

    //create the Category Data set
    private CategoryDataset generateGraphCategoryDataSet(Map<Integer, Double> processedData) {
        double[][] dataSet = buildDataSetFromProcessedData(processedData);
        return DatasetUtilities.createCategoryDataset("Relative Speed", "", dataSet);
    }

    //build a matrix data set from the processed Data
    private double[][] buildDataSetFromProcessedData(Map<Integer, Double> processedData) {
        double[][] dataSet = new double[100][noOfBins];
        for (int i = 0; i < processedData.size(); i++) {
            dataSet[0][i] = processedData.get(i);
        }
        return dataSet;
    }

    private void generateAverageLinkSpeedGraph(CategoryDataset dataSet, int iterationNumber) {
        // Settings legend and title for the plot
        String plotTitle = "Average Link speed over a day [used links only]";
        String x_axis = "Bin";
        String y_axis = "AverageLinkSpeed";
        int width = 800;
        int height = 600;

        // Setting orientation for the ploteStackedBarChart
        PlotOrientation orientation = PlotOrientation.VERTICAL;

        // Create the chart
        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        //Get the category plot from the chart
        CategoryPlot plot = chart.getCategoryPlot();

        //add the sorted frequencies to the legend
        LegendItemCollection legendItems = new LegendItemCollection();
        legendItems.add(new LegendItem("% Avg Link Speed", getColor(0)));
        plot.getRenderer().setSeriesPaint(0, getColor(0));
        plot.setFixedLegendItems(legendItems);
        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, outputFileName + ".png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Color getColor(int i) {
        if (i < colors.size()) {
            return colors.get(i);
        } else {
            return getRandomColor();
        }
    }

    private Color getRandomColor() {

        Random rand = new Random();

        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();

        return new Color(r, g, b);
    }


    public double getAverageSpeedPercentageOfBin(int bin, TravelTimeCalculator travelTimeCalculator) {
        try {
            Map<Integer, Double> processedData = generateInputDataForGraph(travelTimeCalculator);
            double[][] dataSet = buildDataSetFromProcessedData(processedData);
            double[] averageSpeedPercentages = dataSet[0];
            return averageSpeedPercentages[bin];
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public double[] getAverageSpeedPercentagesOfAllBins(TravelTimeCalculator travelTimeCalculator) {
        Map<Integer, Double> processedData = generateInputDataForGraph(travelTimeCalculator);
        double[][] dataSet = buildDataSetFromProcessedData(processedData);
        return dataSet[0];
    }

    public int getNumberOfBins() {
        return noOfBins;
    }
}
