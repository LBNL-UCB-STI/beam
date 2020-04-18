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
import java.util.Arrays;
import java.util.Collections;
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

    static final String OUTPUT_FILE_NAME = "physsimLinkAverageSpeedPercentage";
    private static final int BIN_SIZE = 3600;
    private static final List<Color> COLORS = Collections.unmodifiableList(
            Arrays.asList(Color.GREEN, Color.BLUE)
    );
    private static final int NUM_OF_BIN_TEST_MODE = 24;

    private final int numOfBins;
    private final BeamConfig beamConfig;
    private final Network network;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;

    public PhyssimCalcLinkSpeedStats(Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, BeamConfig beamConfig) {
        this.network = network;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.beamConfig = beamConfig;

        numOfBins = isTestMode()
                ? NUM_OF_BIN_TEST_MODE
                : getNumOfBinsFromConfig(beamConfig);
    }

    private int getNumOfBinsFromConfig(BeamConfig beamConfig) {
        double endTime = Time.parseTime(beamConfig.matsim().modules().qsim().endTime());
        Double numOfTimeBins = endTime / this.beamConfig.beam().physsim().linkStatsBinSize();
        numOfTimeBins = Math.floor(numOfTimeBins);
        return numOfTimeBins.intValue() + 1;
    }

    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        Map<Integer, Double> processedData = generateInputDataForGraph(travelTime);
        CategoryDataset dataSet = generateGraphCategoryDataSet(processedData);
        if (this.outputDirectoryHierarchy != null) {
            if (!isTestMode()) {
                this.writeCSV(processedData, outputDirectoryHierarchy.getIterationFilename(iteration, OUTPUT_FILE_NAME + ".csv"));
            }
            if (beamConfig.beam().outputs().writeGraphs()) {
                generateAverageLinkSpeedGraph(dataSet, iteration);
            }
        }
    }

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

    private boolean isTestMode() {
        return beamConfig == null;
    }

    private Map<Integer, Double> generateInputDataForGraph(TravelTime travelTime) {
        return IntStream.range(0, numOfBins).parallel().boxed()
                .collect(Collectors.toMap(Function.identity(),
                        idx -> calcLinkAvgSpeedPercentage(travelTime, idx)));
    }

    private double calcLinkAvgSpeedPercentage(TravelTime travelTime, int idx) {
        List<Double> avgSpeeds = this.network.getLinks().values().parallelStream()
                .filter(link -> IntStream.range(0, numOfBins).parallel() // filter links with average speed >= freeSpeed
                        .anyMatch(i -> calcSpeedRatio(i, link, travelTime) >= 1))
                .map(link -> calcSpeedRatio(idx, link, travelTime))
                .collect(Collectors.toList());
        return (avgSpeeds.stream().mapToDouble(Double::doubleValue).sum() / avgSpeeds.size()) * 100;
    }

    private double calcSpeedRatio(int idx, Link link, TravelTime travelTime) {

        double freeSpeed = link.getFreespeed(idx * BIN_SIZE);
        double linkLength = link.getLength();
        double averageTime = travelTime.getLinkTravelTime(link, idx * BIN_SIZE, null, null);
        double averageSpeed = linkLength / averageTime;
        return averageSpeed / freeSpeed;
    }

    private CategoryDataset generateGraphCategoryDataSet(Map<Integer, Double> processedData) {
        double[][] dataSet = buildDataSetFromProcessedData(processedData);
        return DatasetUtilities.createCategoryDataset("Relative Speed", "", dataSet);
    }

    private double[][] buildDataSetFromProcessedData(Map<Integer, Double> processedData) {
        double[][] dataSet = new double[100][numOfBins];
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

        PlotOrientation orientation = PlotOrientation.VERTICAL;

        final JFreeChart chart = ChartFactory
                .createStackedBarChart(plotTitle, x_axis, y_axis, dataSet, orientation, false, true, true);
        chart.setBackgroundPaint(new Color(255, 255, 255));

        CategoryPlot plot = chart.getCategoryPlot();

        //add the sorted frequencies to the legend
        LegendItemCollection legendItems = new LegendItemCollection();
        legendItems.add(new LegendItem("% Avg Link Speed", getColor(0)));
        plot.getRenderer().setSeriesPaint(0, getColor(0));
        plot.setFixedLegendItems(legendItems);
        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, OUTPUT_FILE_NAME + ".png");
        try {
            ChartUtilities.saveChartAsPNG(new File(graphImageFile), chart, width,
                    height);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Color getColor(int i) {
        if (i < COLORS.size()) {
            return COLORS.get(i);
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

    public double getAverageSpeedPercentageOfBin(int bin, TravelTime travelTime) {
        try {
            Map<Integer, Double> processedData = generateInputDataForGraph(travelTime);
            double[][] dataSet = buildDataSetFromProcessedData(processedData);
            double[] averageSpeedPercentages = dataSet[0];
            return averageSpeedPercentages[bin];
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public double[] getAverageSpeedPercentagesOfAllBins(TravelTime travelTime) {
        Map<Integer, Double> processedData = generateInputDataForGraph(travelTime);
        double[][] dataSet = buildDataSetFromProcessedData(processedData);
        return dataSet[0];
    }

    public int getNumberOfBins() {
        return numOfBins;
    }
}
