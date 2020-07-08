package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.IterationSummaryAnalysis;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import beam.sim.common.GeoUtils;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.SimulationMetricCollector;
import beam.utils.DebugLib;
import com.conveyal.r5.transit.TransportNetwork;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.misc.Time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.*;

import static java.lang.Integer.max;

/**
 * @author abid
 */
public class RideHailWaitingAnalysis implements GraphAnalysis, IterationSummaryAnalysis {

    private static final Logger log = LoggerFactory.getLogger(DebugLib.class);

    public static final String RIDE_HAIL = "ride_hail";
    public static final String RIDE_HAIL_POOLED = "ride_hail_pooled";
    public static final String WALK_TRANSIT = "walk_transit";
    private final OutputDirectoryHierarchy ioController;

    public RideHailWaitingAnalysis(StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation,
                                   SimulationMetricCollector simMetricCollector, OutputDirectoryHierarchy ioController) {
        this.statComputation = statComputation;
        this.simMetricCollector = simMetricCollector;
        this.ioController = ioController;
    }

    public static class WaitingStatsComputation implements StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> {

        @Override
        public Tuple<Map<Integer, Map<Double, Integer>>, double[][]> compute(Tuple<List<Double>, Map<Integer, List<Double>>> stat) {
            Map<Integer, Map<Double, Integer>> hourModeFrequency = calculateHourlyData(stat.getSecond(), stat.getFirst());
            double[][] data = buildModesFrequencyDataset(hourModeFrequency, stat.getFirst());
            return new Tuple<>(hourModeFrequency, data);
        }

        /**
         * Calculate the data and populate the dataset i.e. "hourModeFrequency"
         */
        public static Map<Integer, Map<Double, Integer>> calculateHourlyData(Map<Integer, List<Double>> hoursTimesMap, List<Double> categories) {

            Map<Integer, Map<Double, Integer>> hourModeFrequency = new HashMap<>();

            // to have proper borders in graph
            Map<Double, Integer> zeroValues0 = new HashMap<>();
            Map<Double, Integer> zeroValues24 = new HashMap<>();
            for (double cat : categories) {
                zeroValues0.put(cat, 0);
                zeroValues24.put(cat, 0);
            }
            hourModeFrequency.put(0, zeroValues0);
            hourModeFrequency.put(24, zeroValues24);

            Set<Integer> hours = hoursTimesMap.keySet();

            for (Integer hour : hours) {
                List<Double> listTimes = hoursTimesMap.get(hour);
                for (double time : listTimes) {
                    Double category = getCategory(time, categories);

                    Map<Double, Integer> hourData = hourModeFrequency.get(hour);
                    Integer frequency = 1;
                    if (hourData != null) {
                        frequency = hourData.get(category);
                        frequency = (frequency == null) ? 1 : frequency + 1;
                    } else {
                        hourData = new HashMap<>();
                    }
                    hourData.put(category, frequency);
                    hourModeFrequency.put(hour, hourData);
                }
            }

            return hourModeFrequency;
        }

        private static Double getCategory(double time, List<Double> categories) {
            int i = 0;
            Double categoryUpperBound = null;
            while (i < categories.size()) {
                categoryUpperBound = categories.get(i);
                if (time <= categoryUpperBound) {

                    break;
                }
                i++;
            }
            return categoryUpperBound;
        }

        //    TODO only two significant digits needed this means, 682 enough, no digits there
        private double[] getHoursDataPerTimeRange(Double category, int maxHour, Map<Integer, Map<Double, Integer>> hourModeFrequency) {
            double[] timeRangeOccurrencePerHour = new double[maxHour + 1];

            for (int hour = 0; hour <= maxHour; hour++) {
                Map<Double, Integer> hourData = hourModeFrequency.get(hour);
                timeRangeOccurrencePerHour[hour] = (hourData == null || hourData.get(category) == null) ? 0 : hourData.get(category);

            }
            return timeRangeOccurrencePerHour;
        }

        private double[][] buildModesFrequencyDataset(Map<Integer, Map<Double, Integer>> hourModeFrequency, List<Double> categories) {

            List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());

            if (hoursList.isEmpty())
                return null;

            int maxHour = numberOfTimeBins;

            double[][] dataset = new double[categories.size()][maxHour + 1];

            for (int i = 0; i < categories.size(); i++) {
                dataset[i] = getHoursDataPerTimeRange(categories.get(i), maxHour, hourModeFrequency);
            }
            return dataset;
        }
    }

    private static final String graphTitle = "Ride Hail Waiting Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Frequency binned by Waiting Time";
    static final String fileName = "rideHailWaitingHistogram";
    static final String rideHailIndividualWaitingTimesFileBaseName = "rideHailIndividualWaitingTimes";
    private static final String rideHailWaitingSingleStatsFileBaseName = "rideHailWaitingSingleStats";
    private double lastMaximumTime = 0;
    private double lastHourWrittenToStats = 0;
    private boolean writeGraph;
    private final List<RideHailWaitingIndividualStat> rideHailWaitingIndividualStatList = new ArrayList<>();
    private final Map<String, Event> rideHailWaiting = new HashMap<>();
    private final Map<String, Double> ptWaiting = new HashMap<>();
    private final Map<Integer, List<Double>> hoursTimesMap = new HashMap<>();
    private final Map<Integer, Double> hoursSingleTimesMap = new HashMap<>();
    private double waitTimeSum = 0;   //sum of all wait times experienced by customers
    private int rideHailCount = 0;   //later used to calculate average wait time experienced by customers
    private double totalPTWaitingTime = 0.0;
    private int numOfTrips = 0;
    private final StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation;

    private static final Double categoryValueMax = Double.MAX_VALUE;
    private static final Double categoryValueBeforeMax = 60.0;
    private final SimulationMetricCollector simMetricCollector;

    private static int numberOfTimeBins;

    private GeoUtils geo;
    private TransportNetwork transportNetwork;

    public RideHailWaitingAnalysis(StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation,
                                   BeamConfig beamConfig,
                                   SimulationMetricCollector simMetricCollector,
                                   GeoUtils geo,
                                   TransportNetwork transportNetwork, OutputDirectoryHierarchy ioController) {
        this.statComputation = statComputation;
        this.writeGraph = beamConfig.beam().outputs().writeGraphs();
        this.simMetricCollector = simMetricCollector;
        this.geo = geo;
        this.transportNetwork = transportNetwork;
        numberOfTimeBins = calculateNumOfTimeBins(beamConfig);
        this.ioController = ioController;
    }

    private int calculateNumOfTimeBins(BeamConfig beamConfig) {
        final int timeBinSize = beamConfig.beam().agentsim().timeBinSize();
        String endTime = beamConfig.matsim().modules().qsim().endTime();
        double _endTime = Time.parseTime(endTime);
        double _noOfTimeBins = _endTime / timeBinSize;
        _noOfTimeBins = Math.floor(_noOfTimeBins);

        return (int) _noOfTimeBins + 1;
    }

    @Override
    public void resetStats() {
        waitTimeSum = 0;
        numOfTrips = 0;
        rideHailCount = 0;
        totalPTWaitingTime = 0.0;
        lastMaximumTime = 0;
        lastHourWrittenToStats = 0;
        ptWaiting.clear();
        rideHailWaiting.clear();
        hoursTimesMap.clear();
        hoursSingleTimesMap.clear();
        rideHailWaitingIndividualStatList.clear();
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof ModeChoiceEvent) {
            ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
            String mode = modeChoiceEvent.mode;
            if (mode.equalsIgnoreCase(RIDE_HAIL) || mode.equalsIgnoreCase(RIDE_HAIL_POOLED)) {

                Id<Person> personId = modeChoiceEvent.getPersonId();
                rideHailWaiting.put(personId.toString(), event);
            }
            if (mode.equalsIgnoreCase(WALK_TRANSIT)) {
                Id<Person> personId = modeChoiceEvent.getPersonId();
                ptWaiting.put(personId.toString(), event.getTime());
            }

        } else if (event instanceof PersonEntersVehicleEvent) {
            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent) event;
            String vehicleId = personEntersVehicleEvent.getVehicleId().toString();
            Id<Person> personId = personEntersVehicleEvent.getPersonId();
            String pId = personId.toString();

            // This rideHailVehicle check is put here again to remove the non rideHail vehicleId which were coming due the
            // another occurrence of modeChoice event because of replanning event.
            if (rideHailWaiting.containsKey(personId.toString()) && vehicleId.contains("rideHailVehicle")) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) rideHailWaiting.get(pId);
                double difference = personEntersVehicleEvent.getTime() - modeChoiceEvent.getTime();
                processRideHailWaitingTimes(modeChoiceEvent, difference);
                processRideHailingSingleWaitingTimes(modeChoiceEvent, difference);

                // Building the RideHailWaitingIndividualStat List
                RideHailWaitingIndividualStat rideHailWaitingIndividualStat = new RideHailWaitingIndividualStat();
                rideHailWaitingIndividualStat.time = modeChoiceEvent.getTime();
                rideHailWaitingIndividualStat.personId = pId;
                rideHailWaitingIndividualStat.vehicleId = vehicleId;
                rideHailWaitingIndividualStat.waitingTime = difference;
                rideHailWaitingIndividualStat.modeChoice = modeChoiceEvent.mode;
                rideHailWaitingIndividualStatList.add(rideHailWaitingIndividualStat);


                // Remove the personId from the list of ModeChoiceEvent
                rideHailWaiting.remove(pId);
            }
            // added summary stats for totalPTWaitingTime
            if (ptWaiting.containsKey(pId) && vehicleId.contains("body")) {
                totalPTWaitingTime += event.getTime() - ptWaiting.get(pId);
                numOfTrips++;
                ptWaiting.remove(pId);
            }
        }
    }

    //Prepare graph for each iteration
    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(event.getIteration());
        if (model == null)
            model = new RideHailDistanceRowModel();
        model.setRideHailWaitingTimeSum(this.waitTimeSum);
        model.setTotalRideHailCount(this.rideHailCount);
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);
        List<Double> listOfBounds = getCategories();
        Tuple<Map<Integer, Map<Double, Integer>>, double[][]> data = statComputation.compute(new Tuple<>(listOfBounds, hoursTimesMap));
        if (writeGraph) {
            CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph(data.getSecond());
            if (modesFrequencyDataset != null) {
                createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
            }
        }

        writeToCSV(event.getIteration(), data.getFirst());
        writeRideHailWaitingIndividualStatCSV(event.getIteration());

        if (writeGraph) {
            double[][] singleStatsData = computeGraphDataSingleStats(hoursSingleTimesMap);
            CategoryDataset singleStatsDataset = GraphUtils.createCategoryDataset("", "", singleStatsData);
            createSingleStatsGraph(singleStatsDataset, event.getIteration());
        }
        writeRideHailWaitingSingleStatCSV(event.getIteration(), hoursSingleTimesMap);

        writeWaitingTimeToStats(hoursTimesMap, listOfBounds);
    }

    @Override
    public Map<String, Double> getSummaryStats() {
        return new HashMap<String, Double>() {{
            put("averageOnDemandRideWaitTimeInMin", waitTimeSum / max(rideHailCount, 1));
            put("averageMTWaitingTimeInSec", totalPTWaitingTime / max(numOfTrips, 1));
        }};
    }

    private void writeRideHailWaitingIndividualStatCSV(int iteration) {
        String csvFileName = ioController.getIterationFilename(iteration, rideHailIndividualWaitingTimesFileBaseName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "timeOfDayInSeconds,personId,rideHailVehicleId,waitingTimeInSeconds,modeChoice";

            out.write(heading);
            out.newLine();

            for (RideHailWaitingIndividualStat rideHailWaitingIndividualStat : rideHailWaitingIndividualStatList) {

                String line = rideHailWaitingIndividualStat.time + "," +
                        rideHailWaitingIndividualStat.personId + "," +
                        rideHailWaitingIndividualStat.vehicleId + "," +
                        rideHailWaitingIndividualStat.waitingTime + "," +
                        rideHailWaitingIndividualStat.modeChoice;

                out.write(line);

                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private double[][] computeGraphDataSingleStats(Map<Integer, Double> stat) {
        double[][] data = new double[1][numberOfTimeBins];
        for (Integer key : stat.keySet()) {
            if (key >= data[0].length) {
                DebugLib.emptyFunctionForSettingBreakPoint();
            }
            data[0][key] = stat.get(key);
        }
        return data;
    }

    private void writeRideHailWaitingSingleStatCSV(int iteration, Map<Integer, Double> hourModeFrequency) {
        String csvFileName = ioController.getIterationFilename(iteration, rideHailWaitingSingleStatsFileBaseName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "WaitingTime(sec),Hour";
            out.write(heading);
            out.newLine();
            for (int i = 0; i < numberOfTimeBins; i++) {
                Double inner = hourModeFrequency.get(i);
                String line = (inner == null) ? "0" : "" + Math.round(inner * 100.0) / 100.0;
                line += "," + i;
                out.write(line);
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void processRideHailWaitingTimes(ModeChoiceEvent event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        if (simMetricCollector.metricEnabled("ride-hail-waiting-time-map")) {
            try {
                int linkId = Integer.parseInt(event.location);
                Coord coord = geo.coordOfR5Edge(transportNetwork.streetLayer, linkId);
                simMetricCollector.writeIterationMapPoint("ride-hail-waiting-time-map", event.getTime(), waitingTime, coord.getY(), coord.getX(), false);
            } catch (NumberFormatException e) {
                log.error("Can't parse 'event.location' as Integer. Event: " + event.toString());
            }
        }

        waitingTime = waitingTime / 60;

        List<Double> timeList = hoursTimesMap.get(hour);
        if (timeList == null) {
            timeList = new ArrayList<>();
        }
        timeList.add(waitingTime);
        this.waitTimeSum += waitingTime;
        this.rideHailCount++;
        hoursTimesMap.put(hour, timeList);

        // to write metrics once per hour
        if (lastHourWrittenToStats < hour) {
            lastHourWrittenToStats = hour;
            writeWaitingTimeToStats(hoursTimesMap, getCategories());
        }
    }

    private void writeWaitingTimeToStats(Map<Integer, List<Double>> hourToWaitings, List<Double> categories) {
        if (simMetricCollector.metricEnabled("ride-hail-waiting-time")) {
            Map<Integer, Map<Double, Integer>> hourToCategories = WaitingStatsComputation.calculateHourlyData(hourToWaitings, categories);

            DecimalFormat df = new DecimalFormat("##");
            df.setRoundingMode(RoundingMode.FLOOR);

            hourToCategories.forEach((hour, catToCnt) -> catToCnt.forEach((category, count) -> {
                final String categoryName = category.equals(categoryValueMax)
                        ? df.format(categoryValueBeforeMax) + "+"
                        : df.format(category);

                HashMap<String, String> tags = new HashMap<>(1);
                tags.put("category", categoryName);
                simMetricCollector.writeIterationJava("ride-hail-waiting-time", hour * 60 * 60, count, tags, true);
            }));
        }
    }

    private void processRideHailingSingleWaitingTimes(Event event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        if (waitingTime > lastMaximumTime) {
            lastMaximumTime = waitingTime;
        }

        Double timeList = hoursSingleTimesMap.get(hour);
        if (timeList == null) {
            timeList = waitingTime;
        } else {
            timeList += waitingTime;
        }
        hoursSingleTimesMap.put(hour, timeList);
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph(double[][] dataset) {
        return dataset == null
                ? null
                : GraphUtils.createCategoryDataset("Time ", "", dataset);
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, true);
        CategoryPlot plot = chart.getCategoryPlot();

        // Legends
        List<String> legends = getLegends(getCategories());
        GraphUtils.plotLegendItems(plot, legends, dataset.getRowCount());

        // Writing graph to image file
        String graphImageFile = ioController.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createSingleStatsGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, false);
        GraphUtils.setColour(chart, 1);
        // Writing graph to image file
        String graphImageFile = ioController.getIterationFilename(iterationNumber, rideHailWaitingSingleStatsFileBaseName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    private void writeToCSV(int iterationNumber, Map<Integer, Map<Double, Integer>> hourModeFrequency) {
        String csvFileName = ioController.getIterationFilename(iterationNumber, fileName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "WaitingTime,Hour,Count";
            out.write(heading);
            out.newLine();

            List<Double> categories = getCategories();

            for (Double category : categories) {
                Double _category = getRoundedCategoryUpperBound(category);

                String line;
                for (int i = 0; i < numberOfTimeBins; i++) {
                    Map<Double, Integer> innerMap = hourModeFrequency.get(i);
                    line = (innerMap == null || innerMap.get(category) == null) ? "0" : innerMap.get(category).toString();
                    if (category > 60) {
                        line = "60+," + i + "," + line;
                    } else {
                        line = _category + "," + i + "," + line;
                    }
                    out.write(line);
                    out.newLine();
                }
            }
            out.flush();
        } catch (IOException e) {
            log.error("exception occurred due to ", e);
        }
    }

    // Utility Methods
    private List<Double> getCategories() {

        List<Double> listOfBounds = new ArrayList<>();
        listOfBounds.add(2.0);
        listOfBounds.add(5.0);
        listOfBounds.add(10.0);
        listOfBounds.add(20.0);
        listOfBounds.add(30.0);
        listOfBounds.add(categoryValueBeforeMax);
        listOfBounds.add(categoryValueMax);

        return listOfBounds;
    }

    private List<String> getLegends(List<Double> categories) {

        List<String> legends = new ArrayList<>();
        for (Double category : categories) {

            double legend = getRoundedCategoryUpperBound(category);
            if (legend > 60)
                legends.add("60+");
            else {
                legends.add(category.intValue() + "min");
            }

        }
        //Collections.sort(legends);
        return legends;
    }

    private double getRoundedCategoryUpperBound(double category) {
        return Math.round(category * 100) / 100.0;
    }

    static class RideHailWaitingIndividualStat {
        double time;
        String personId;
        String vehicleId;
        double waitingTime;
        String modeChoice;
    }
}
