package beam.analysis.physsim;

import beam.analysis.plots.GraphUtils;
import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamConfigChangesObserver;
import beam.sim.config.BeamConfig;
import beam.utils.BeamCalcLinkStats;
import beam.utils.VolumesAnalyzerFixed;
import org.jfree.chart.*;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.misc.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class PhyssimCalcLinkStats implements BeamConfigChangesObserver {

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
    private final BeamCalcLinkStats linkStats;
    private VolumesAnalyzer volumes;

    public PhyssimCalcLinkStats(Network network, OutputDirectoryHierarchy controlerIO, BeamConfig beamConfig,
                                TravelTimeCalculatorConfigGroup ttcConfigGroup, BeamConfigChangesObservable beamConfigChangesObservable) {
        this.network = network;
        this.controllerIO = controlerIO;
        this.beamConfig = beamConfig;

        if (isNotTestMode()) {
            binSize = this.beamConfig.beam().physsim().linkStatsBinSize();

            String endTime = beamConfig.matsim().modules().qsim().endTime();
            Double _endTime = Time.parseTime(endTime);
            Double _noOfTimeBins = _endTime / binSize;
            _noOfTimeBins = Math.floor(_noOfTimeBins);
            noOfBins = _noOfTimeBins.intValue() + 1;
        }
        beamConfigChangesObservable.addObserver(this);

        linkStats = new BeamCalcLinkStats(network, ttcConfigGroup);
    }

    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        linkStats.addData(volumes, travelTime);
        processData(iteration, travelTime);
        if (this.controllerIO != null) {
            if (isNotTestMode() && writeLinkStats(iteration)) {
                linkStats.writeFile(this.controllerIO.getIterationFilename(iteration, "linkstats_unmodified.csv.gz"));
            }
            if (beamConfig.beam().outputs().writeGraphs()) {
                CategoryDataset dataset = buildAndGetGraphCategoryDataset();
                createModesFrequencyGraph(dataset, iteration);
            }
        }
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

    private void processData(int iteration, TravelTime travelTime) {
        for (int idx = 0; idx < noOfBins; idx++) {
            for (Link link : this.network.getLinks().values()) {
                double freeSpeed = link.getFreespeed(idx * binSize);

                double linkLength = link.getLength();

                double averageTime = travelTime.getLinkTravelTime(link, idx * binSize, null, null);

                double minSpeed = this.beamConfig.beam().physsim().quick_fix_minCarSpeedInMetersPerSecond();

                double averageSpeed = linkLength / averageTime;

                double averageSpeedToFreeSpeedRatio = averageSpeed / freeSpeed;

                double relativeSpeed = Math.max((Math.round(averageSpeedToFreeSpeedRatio * 50.0) / 10),minSpeed);

                Map<Integer, Integer> hoursDataMap = relativeSpeedFrequenciesPerBin.get(relativeSpeed);

                if (hoursDataMap != null) {
                    hoursDataMap.merge(idx, 1, (a, b) -> a + b);
                } else {
                    hoursDataMap = new HashMap<>();
                    hoursDataMap.put(idx, 1);
                }

                relativeSpeedFrequenciesPerBin.put(relativeSpeed, hoursDataMap);
            }
        }
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


    private CategoryDataset buildAndGetGraphCategoryDataset() {
        double[][] dataset = buildModesFrequencyDataset();
        return GraphUtils.createCategoryDataset("Relative Speed", "", dataset);
    }

    List<Double> getSortedListRelativeSpeedCategoryList() {
        List<Double> relativeSpeedsCategoriesList = new ArrayList<>(relativeSpeedFrequenciesPerBin.keySet());
        Collections.sort(relativeSpeedsCategoriesList);
        return relativeSpeedsCategoriesList;
    }

    private double[][] buildModesFrequencyDataset() {
        List<Double> relativeSpeedsCategoriesList = getSortedListRelativeSpeedCategoryList();
        double[][] dataset = new double[0][];

        Optional<Double> optionalMaxRelativeSpeedsCategories = relativeSpeedsCategoriesList.stream().max(Comparator.naturalOrder());

        if(optionalMaxRelativeSpeedsCategories.isPresent()) {
            int maxRelativeSpeedsCategories = optionalMaxRelativeSpeedsCategories.get().intValue();
            dataset = new double[maxRelativeSpeedsCategories+1][noOfBins];

            for (int i = 0; i <= maxRelativeSpeedsCategories; i++) {

                Map<Integer, Integer> relativeSpeedBins = relativeSpeedFrequenciesPerBin.getOrDefault((double)i, new HashMap<>());

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
        List<Double> relativeSpeedsCategoriesList = new ArrayList<>(relativeSpeedFrequenciesPerBin.keySet());

        int max = Collections.max(relativeSpeedsCategoriesList).intValue();

        for (int i = 0; i <= max ; i++) {
            legendItems.add(new LegendItem(String.valueOf(i), getColor(i)));
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
        this.linkStats.reset();
        volumes = new VolumesAnalyzerFixed(3600, travelTimeCalculatorConfigGroup.getMaxTime() - 1, network);
        eventsManager.addHandler(volumes);
        this.relativeSpeedFrequenciesPerBin.clear();
    }

    public void clean(){
        this.linkStats.reset();
    }

    @Override
    public void update(BeamConfigChangesObservable observable, BeamConfig updatedBeamConfig) {
        this.beamConfig = updatedBeamConfig;
    }
}
