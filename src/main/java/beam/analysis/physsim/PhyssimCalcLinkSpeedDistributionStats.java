package beam.analysis.physsim;

import beam.analysis.plots.GraphUtils;
import beam.sim.config.BeamConfig;
import com.google.common.base.Suppliers;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Bhavya Latha Bandaru.
 * This class computes the distribution of free flow speed (in both m/s and %) over the network.
 */
public class PhyssimCalcLinkSpeedDistributionStats {
    private final Logger log = LoggerFactory.getLogger(PhyssimCalcLinkSpeedDistributionStats.class);

    private static int noOfBins = 24;
    private final BeamConfig beamConfig;
    private final Network network;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    static final String outputAsSpeedUnitFileName = "physsimFreeFlowSpeedDistribution";
    private static final String outputAsPercentageFileName = "physsimFreeFlowSpeedDistributionAsPercentage";

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
     *
     * @param iteration the count of the current iteration
     */
    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        //generate the graph input for the free flow speed distribution
        Map<Integer, Integer> processedSpeedDistributionData = generateInputDataForFreeFlowSpeedGraph(noOfBins, this.network);
        //generate  data for the free flow speed distribution
        Supplier<List<SpeedWithFrequency>> speedDataList = Suppliers.memoize(() -> buildDataSetFromSpeedData(processedSpeedDistributionData));
        //generate the graph input for the link efficiencies
        Supplier<Map<Double, Integer>> processedSpeedDistributionAsPercentageData = Suppliers.memoize(() ->
                generateInputDataForLinkEfficiencies(travelTime));

        if (this.outputDirectoryHierarchy != null) {
            //If not running in test mode , write output to a csv file
            if (isNotTestMode()) {
                //write data outputs to CSV
                this.writeCSV(speedDataList.get(), outputDirectoryHierarchy.getIterationFilename(iteration, outputAsSpeedUnitFileName + ".csv"), "freeSpeedInMetersPerSecond");
                this.writeCSV(processedSpeedDistributionAsPercentageData.get(), outputDirectoryHierarchy.getIterationFilename(iteration, outputAsPercentageFileName + ".csv"), "linkEfficiencyInPercentage");
            }
            //generate the required charts - frequency over speed (as m/s)
            if (beamConfig.beam().outputs().writeGraphs()) {
                //generate category data set for free flow speed distribution
                CategoryDataset dataSetForSpeedTest = generateSpeedDistributionDataSet(speedDataList.get());
                generateSpeedDistributionBarChart(dataSetForSpeedTest, iteration);

                //generate the category data set for link efficiencies
                CategoryDataset dataSetForSpeedAsPercentage = generateLinkEfficienciesDataSet(processedSpeedDistributionAsPercentageData.get());
                generateSpeedDistributionAsPercentageChart(dataSetForSpeedAsPercentage, iteration);
            }
        }
    }

    private CategoryDataset generateSpeedDistributionDataSet(List<SpeedWithFrequency> speedDataList) {
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        speedDataList.forEach(entry ->
                result.addValue(Integer.valueOf(entry.frequency), Integer.valueOf(0), Integer.valueOf(entry.speed)));
        return result;
    }

    private CategoryDataset generateLinkEfficienciesDataSet(Map<Double, Integer> generatedDataMap) {
        try {
            Map<Integer, Integer> converterMap = new HashMap<>();
            generatedDataMap.forEach((k, v) -> {
                int category = (int) Math.round(k) / 10;
                category = ((category >= 0 && category != 10) ? (category + 1) : category) * 10;
                Integer value = converterMap.getOrDefault(category, 0);
                converterMap.put(category, value + v);
            });

            Map<Integer, Integer> data = IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> i*10)
                    .collect(Collectors.toMap(i -> i, i -> converterMap.getOrDefault(i,0)));
            return GraphUtils.createCategoryDataset(data);
        } catch (Exception e) {
            log.error("exception occurred due to ", e);
        }

        return GraphUtils.createCategoryDataset(Collections.emptyMap());
    }

    /**
     * Helper method that writes the final data to a CSV file
     *
     * @param data           the input data required to generate the charts
     * @param outputFilePath path to the CSV file
     * @param heading        header string for the CSV file
     */
    private void writeCSV(List<SpeedWithFrequency> data, String outputFilePath, String heading) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath))) {
            String completeHeading = heading + ",numberOfLinks\n";
            bw.write(completeHeading);
            data.forEach(entry -> {
                try {
                    bw.write(entry.speed + "," + entry.frequency + "\n");
                } catch (IOException e) {
                    log.error("exception occurred due to ", e);
                }
            });
        } catch (Exception e) {
            log.error("exception occurred due to ", e);
        }
    }

    /**
     * Helper method that writes the final data to a CSV file
     *
     * @param dataMap        the input data required to generate the charts
     * @param outputFilePath path to the CSV file
     * @param heading        header string for the CSV file
     */
    private void writeCSV(Map<Double, Integer> dataMap, String outputFilePath, String heading) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath))) {
            String completeHeading = heading + ",linkEfficiencyRounded,numberOfLinks\n";
            bw.write(completeHeading);
            dataMap.forEach((k, v) -> {
                try {
                    bw.write(k + "," + (int) Math.round(k) + "," + v + "\n");
                } catch (IOException e) {
                    log.error("exception occurred due to ", e);
                }
            });
        } catch (Exception e) {
            log.error("exception occurred due to ", e);
        }
    }

    // A helper method to test if the application is running in test mode or not
    private boolean isNotTestMode() {
        return beamConfig != null;
    }

    /**
     * Generates input data used to generate free flow speed distribution chart
     *
     * @return input generated data as map ( speed in m/s -> frequency )
     */
    public Map<Integer, Integer> generateInputDataForFreeFlowSpeedGraph(int binsCount, Network network) {
        Map<Integer, Integer> freeFlowSpeedFrequencies = new HashMap<>();
        Stream.iterate(0, x -> x)
                .limit(binsCount)
                .forEach(bin -> network.getLinks().values()
                        .stream()
                        .map(link -> link.getFreespeed(bin * 3600))
                        .map(fs -> (int) Math.round(fs))
                        .forEach(freeSpeed -> {
                            Integer frequencyCount = freeFlowSpeedFrequencies.getOrDefault(freeSpeed, 0);
                            freeFlowSpeedFrequencies.put(freeSpeed, frequencyCount + 1);
                        }));
        return freeFlowSpeedFrequencies;
    }

    /**
     * Generates input data used to generate frequencies of link efficiencies
     *
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
                Integer frequencyCount1 = frequencyOfEfficiencies.getOrDefault(averageSpeedToFreeSpeedRatio * 100, 0);
                frequencyOfEfficiencies.put(averageSpeedToFreeSpeedRatio * 100, frequencyCount1 + 1);
            }
        }
        return frequencyOfEfficiencies;
    }

    /**
     * Generate a data, used to generate category data set for stacked bar chart
     *
     * @param generatedDataMap input data generated as map
     * @return list of data ordered by speed
     */
    private List<SpeedWithFrequency> buildDataSetFromSpeedData(Map<Integer, Integer> generatedDataMap) {
        return generatedDataMap.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(x -> new SpeedWithFrequency(x.getKey(), x.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Generates a free flow speed (as m/s) distribution stacked bar chart
     *
     * @param dataSet         the input data set for the chart
     * @param iterationNumber The number of current iteration
     */
    private void generateSpeedDistributionBarChart(CategoryDataset dataSet, int iterationNumber) {
        // Settings legend and title for the plot
        String plotTitle = "Free Flow Speed Distribution";
        String x_axis = "Free Speed (m/s)";
        String y_axis = "Frequency";
        int width = 1000;
        int height = 600;

        // Create the chart
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataSet, plotTitle, x_axis, y_axis, false);

        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, outputAsSpeedUnitFileName + ".png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, width, height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    /**
     * Generates a free flow speed (as %) distribution line chart
     *
     * @param dataSet         the input data set for the chart
     * @param iterationNumber The number of current iteration
     */
    private void generateSpeedDistributionAsPercentageChart(CategoryDataset dataSet, int iterationNumber) {
        // Settings legend and title for the plot
        String plotTitle = "Free Flow Speed Distribution (as percentage)";
        String x_axis = "Free Speed (%)";
        String y_axis = "Frequency";
        int width = 1000;
        int height = 800;

        // Create the chart
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataSet, plotTitle, x_axis, y_axis, false);

        //Save the chart as image
        String graphImageFile = outputDirectoryHierarchy.getIterationFilename(iterationNumber, outputAsPercentageFileName + ".png");
        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, width, height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private static class SpeedWithFrequency {
        final int speed;
        final int frequency;

        SpeedWithFrequency(int speed, int frequency) {
            this.speed = speed;
            this.frequency = frequency;
        }
    }
}
