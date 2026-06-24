package beam.analysis.physsim;

import beam.agentsim.agents.vehicles.BeamVehicle;
import beam.analysis.plots.GraphUtils;
import beam.physsim.analysis.LinkStatsWithVehicleCategory;
import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamConfigChangesObserver;
import beam.sim.config.BeamConfig;
import beam.utils.VolumesAnalyzerFixed;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class PhyssimCalcLinkStats implements BeamConfigChangesObserver {
    private static final double RELATIVE_SPEED_BUCKET_MULTIPLIER = 50.0;
    private static final double RELATIVE_SPEED_BUCKET_DIVISOR = 10.0;

    private final Logger log = LoggerFactory.getLogger(PhyssimCalcLinkStats.class);

    private static final List<Color> colors = new ArrayList<>();
    private static int noOfBins = 24;
    private static int binSize = 3600;

    // Static Initializer
    static {
        colors.add(Color.GREEN);
        colors.add(Color.BLUE);
        colors.add(Color.GRAY);
        colors.add(Color.PINK);
        colors.add(Color.RED);
        colors.add(Color.MAGENTA);
        colors.add(Color.BLACK);
        colors.add(Color.YELLOW);
        colors.add(Color.CYAN);
    }

    /**
     * The outer map contains the relativeSpeed a double value as the key that defines a relativeSpeed category.
     * The inner map contains the bin id as the key and the frequency as the value for the particular relativeSpeed category.
     */
    private final Map<Double, Map<Integer, Integer>> relativeSpeedFrequenciesPerBin = new HashMap<>();
    private BeamConfig beamConfig;
    private final Network network;
    private final OutputDirectoryHierarchy controllerIO;
    private VolumesAnalyzer volumes;
    private TravelTimeCalculatorConfigGroup ttcConfigGroup;
    private Map<Id<BeamVehicle>, BeamVehicle> vehicleMap;
    private int invalidRelativeSpeedObservationCount;

    public PhyssimCalcLinkStats(Network network, OutputDirectoryHierarchy controlerIO, BeamConfig beamConfig,
                                TravelTimeCalculatorConfigGroup ttcConfigGroup, BeamConfigChangesObservable beamConfigChangesObservable,
                                Map<Id<BeamVehicle>, BeamVehicle> vehicleMap) {
        this.network = network;
        this.controllerIO = controlerIO;
        this.beamConfig = beamConfig;
        this.ttcConfigGroup = ttcConfigGroup;
        this.vehicleMap = vehicleMap;

        if (isNotTestMode()) {
            binSize = this.beamConfig.beam().physsim().linkStatsBinSize();

            String endTime = beamConfig.matsim().modules().qsim().endTime();
            Double _endTime = Time.parseTime(endTime);
            Double _noOfTimeBins = _endTime / binSize;
            _noOfTimeBins = Math.floor(_noOfTimeBins);
            noOfBins = _noOfTimeBins.intValue() + 1;
        }
        beamConfigChangesObservable.addObserver(this);

    }

    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        notifyIterationEnds(iteration, 1, 1, travelTime);
    }

    public void notifyIterationEnds(int iteration, int currentPhysSimIter, int totalPhysSimIters, TravelTime travelTime) {
        boolean shouldWriteLinkStats = isNotTestMode() && writeLinkStats(iteration);
        boolean shouldWriteGraphs = beamConfig.beam().outputs().writeGraphs();
        // Keep data processing enabled in test mode (controllerIO == null) because tests assert relative speed buckets.
        boolean shouldProcessData = !isNotTestMode() || shouldWriteLinkStats || shouldWriteGraphs;

        if (shouldProcessData) {
            processData(iteration, travelTime);
        }

        if (this.controllerIO != null) {
            if (shouldWriteLinkStats) {
                String fileName = getLinkStatsFileName(currentPhysSimIter, totalPhysSimIters);
                String filePath = this.controllerIO.getIterationFilename(iteration, fileName);
                LinkStatsWithVehicleCategory linkStats = new LinkStatsWithVehicleCategory(network, ttcConfigGroup);
                linkStats.writeLinkStatsWithTruckVolumes(volumes, travelTime, filePath);
            }
            if (shouldWriteGraphs) {
                CategoryDataset dataset = buildAndGetGraphCategoryDataset();
                createModesFrequencyGraph(dataset, iteration);
            }
        }
    }

    private String getLinkStatsFileName(int currentPhysSimIter, int totalPhysSimIters) {
        String fileType = linkStatsOutputFileType();
        if (totalPhysSimIters > 1 && currentPhysSimIter < totalPhysSimIters) {
            return String.format("linkstats_unmodified_physSimIter%d.%s", currentPhysSimIter, fileType);
        }
        return String.format("linkstats_unmodified.%s", fileType);
    }

    private boolean isNotTestMode() {
        return controllerIO != null;
    }

    public VolumesAnalyzer getVolumes() {
        return volumes;
    }

    private boolean writeLinkStats(int iterationNumber) {
        int interval = beamConfig.beam().physsim().linkStatsWriteInterval();
        return writeInIteration(iterationNumber, interval);
    }

    private boolean writeInIteration(int iterationNumber, int interval) {
        return interval == 1 || (interval > 0 && iterationNumber % interval == 0);
    }

    private String linkStatsOutputFileType() {
        String fileType = beamConfig.beam().physsim().linkStatsOutputFileType();
        if (fileType == null) return "csv.gz";
        fileType = fileType.trim();
        if (fileType.isEmpty()) return "csv.gz";
        if (fileType.startsWith(".")) fileType = fileType.substring(1);
        return fileType.toLowerCase();
    }

    private void processData(int iteration, TravelTime travelTime) {
        for (int idx = 0; idx < noOfBins; idx++) {
            for (Link link : this.network.getLinks().values()) {
                double freeSpeed = link.getFreespeed(idx * binSize);
                double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);
                OptionalDouble relativeSpeed = computeRelativeSpeedBucket(link.getLength(), averageTime, freeSpeed);
                if (!relativeSpeed.isPresent()) {
                    invalidRelativeSpeedObservationCount++;
                    continue;
                }

                double relativeSpeedBucket = relativeSpeed.getAsDouble();
                Map<Integer, Integer> hoursDataMap = relativeSpeedFrequenciesPerBin.get(relativeSpeedBucket);

                if (hoursDataMap != null) {
                    hoursDataMap.merge(idx, 1, (a, b) -> a + b);
                } else {
                    hoursDataMap = new HashMap<>();
                    hoursDataMap.put(idx, 1);
                }

                relativeSpeedFrequenciesPerBin.put(relativeSpeedBucket, hoursDataMap);
            }
        }
        if (invalidRelativeSpeedObservationCount > 0) {
            log.warn(
                "Skipped {} invalid relative speed observations while building physsim link stats.",
                invalidRelativeSpeedObservationCount
            );
        }
    }

    private OptionalDouble computeRelativeSpeedBucket(double linkLength, double averageTime, double freeSpeed) {
        if (averageTime <= 0 || freeSpeed <= 0 || !Double.isFinite(averageTime) || !Double.isFinite(freeSpeed)) {
            return OptionalDouble.empty();
        }

        double averageSpeed = linkLength / averageTime;
        double averageSpeedToFreeSpeedRatio = averageSpeed / freeSpeed;
        if (!Double.isFinite(averageSpeedToFreeSpeedRatio)) {
            return OptionalDouble.empty();
        }

        double minSpeed = this.beamConfig.beam().physsim().minCarSpeedInMetersPerSecond();
        double relativeSpeed = Math.max(
            Math.round(averageSpeedToFreeSpeedRatio * RELATIVE_SPEED_BUCKET_MULTIPLIER) / RELATIVE_SPEED_BUCKET_DIVISOR,
            minSpeed
        );
        if (!Double.isFinite(relativeSpeed)) {
            return OptionalDouble.empty();
        }

        return OptionalDouble.of(relativeSpeed);
    }

    double getRelativeSpeedOfSpecificHour(int relativeSpeedCategoryIndex, int hour) {
        double[][] dataset = buildModesFrequencyDataset();
        double[] hoursData = dataset[relativeSpeedCategoryIndex];
        return hoursData[hour];
    }

    double getRelativeSpeedCountOfSpecificCategory(int relativeSpeedCategoryIndex) {
        double[][] dataset = buildModesFrequencyDataset();
        double[] hoursData = dataset[relativeSpeedCategoryIndex];
        double count = 0;
        for (double hourCount : hoursData) {
            count = count + hourCount;
        }
        return count;
    }


    List<Double> getSortedListRelativeSpeedCategoryList() {
        List<Double> relativeSpeedsCategoriesList = new ArrayList<>(relativeSpeedFrequenciesPerBin.keySet());
        Collections.sort(relativeSpeedsCategoriesList);
        return relativeSpeedsCategoriesList;
    }

    CategoryDataset buildAndGetGraphCategoryDataset() {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        List<Double> relativeSpeedsCategoriesList = getSortedListRelativeSpeedCategoryList();
        for (Double category : relativeSpeedsCategoriesList) {
            Map<Integer, Integer> relativeSpeedBins = relativeSpeedFrequenciesPerBin.getOrDefault(category, Collections.emptyMap());
            String rowKey = "Relative Speed" + formatRelativeSpeedCategory(category);
            for (int binIndex = 0; binIndex < noOfBins; binIndex++) {
                dataset.addValue(relativeSpeedBins.getOrDefault(binIndex, 0), rowKey, String.valueOf(binIndex));
            }
        }
        return dataset;
    }

    private String formatRelativeSpeedCategory(Double category) {
        if (category == null) {
            return "";
        }
        long integerValue = category.longValue();
        return category == integerValue ? String.valueOf(integerValue) : category.toString();
    }

    private double[][] buildModesFrequencyDataset() {
        List<Double> relativeSpeedsCategoriesList = getSortedListRelativeSpeedCategoryList();
        double[][] dataset = new double[0][];

        Optional<Double> optionalMaxRelativeSpeedsCategories = relativeSpeedsCategoriesList.stream().max(Comparator.naturalOrder());

        if (optionalMaxRelativeSpeedsCategories.isPresent()) {
            int maxRelativeSpeedsCategories = optionalMaxRelativeSpeedsCategories.get().intValue();
            dataset = new double[maxRelativeSpeedsCategories + 1][noOfBins];

            for (int i = 0; i <= maxRelativeSpeedsCategories; i++) {

                Map<Integer, Integer> relativeSpeedBins = relativeSpeedFrequenciesPerBin.getOrDefault((double) i, new HashMap<>());

                double[] relativeSpeedFrequencyPerHour = new double[noOfBins];
                int index = 0;

                for (int binIndex = 0; binIndex < noOfBins; binIndex++) {
                    Integer hourFrequency = relativeSpeedBins.get(binIndex);
                    if (hourFrequency != null) {
                        relativeSpeedFrequencyPerHour[index] = hourFrequency;
                    } else {
                        relativeSpeedFrequencyPerHour[index] = 0;
                    }
                    index = index + 1;
                }
                dataset[i] = relativeSpeedFrequencyPerHour;
            }
        }

        return dataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) {
        String plotTitle = "Relative Network Link Speeds";
        String xaxis = "Hour";
        String yaxis = "# of network links";
        int width = 800;
        int height = 600;

        String graphImageFile = controllerIO.getIterationFilename(iterationNumber, "relativeSpeeds.png");
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, plotTitle, xaxis, yaxis, true);
        CategoryPlot plot = chart.getCategoryPlot();

        LegendItemCollection legendItems = new LegendItemCollection();
        List<Double> relativeSpeedsCategoriesList = getSortedListRelativeSpeedCategoryList();

        for (int i = 0; i < relativeSpeedsCategoriesList.size(); i++) {
            Double category = relativeSpeedsCategoriesList.get(i);
            legendItems.add(new LegendItem(formatRelativeSpeedCategory(category), getColor(i)));
            plot.getRenderer().setSeriesPaint(i, getColor(i));
        }
        plot.setFixedLegendItems(legendItems);

        try {
            GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, width, height);
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
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

    public void notifyIterationStarts(EventsManager eventsManager, TravelTimeCalculatorConfigGroup travelTimeCalculatorConfigGroup) {
        volumes = new VolumesAnalyzerFixed(3600, travelTimeCalculatorConfigGroup.getMaxTime() - 1, network, vehicleMap);
        eventsManager.addHandler(volumes);
        this.relativeSpeedFrequenciesPerBin.clear();
        this.invalidRelativeSpeedObservationCount = 0;
    }

    @Override
    public void update(BeamConfigChangesObservable observable, BeamConfig updatedBeamConfig) {
        this.beamConfig = updatedBeamConfig;
    }
}
