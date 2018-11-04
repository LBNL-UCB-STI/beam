package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import beam.sim.config.BeamConfig;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.misc.Time;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * @author abid
 */
public class RideHailWaitingAnalysis implements GraphAnalysis {

    public RideHailWaitingAnalysis(StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation) {
        this.statComputation = statComputation;
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
        private Map<Integer, Map<Double, Integer>> calculateHourlyData(Map<Integer, List<Double>> hoursTimesMap, List<Double> categories) {

            Map<Integer, Map<Double, Integer>> hourModeFrequency = new HashMap<>();

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

        private Double getCategory(double time, List<Double> categories) {
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
    private static final String yAxisTitle = "Waiting Time (frequencies)";
    private static final String fileName = "rideHail_waitingStats";
    private List<RideHailWaitingIndividualStat> rideHailWaitingIndividualStatList = new ArrayList<>();
    private Map<String, Event> rideHailWaiting = new HashMap<>();
    private Map<Integer, List<Double>> hoursTimesMap = new HashMap<>();
    private double waitTimeSum = 0;   //sum of all wait times experienced by customers
    private int rideHailCount = 0;   //later used to calculate average wait time experienced by customers
    private final StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation;

    private static int numberOfTimeBins = 30;

    public RideHailWaitingAnalysis(StatsComputation<Tuple<List<Double>, Map<Integer, List<Double>>>, Tuple<Map<Integer, Map<Double, Integer>>, double[][]>> statComputation,
                                   BeamConfig beamConfig){
        this.statComputation = statComputation;

        final int timeBinSize = beamConfig.beam().outputs().stats().binSize();

        String endTime = beamConfig.matsim().modules().qsim().endTime();
        Double _endTime = Time.parseTime(endTime);
        Double _noOfTimeBins = _endTime / timeBinSize;
        _noOfTimeBins = Math.floor(_noOfTimeBins);
        numberOfTimeBins = _noOfTimeBins.intValue() + 1;
    }

    @Override
    public void resetStats() {
        waitTimeSum = 0;
        rideHailCount = 0;
        rideHailWaiting.clear();
        hoursTimesMap.clear();
        rideHailWaitingIndividualStatList.clear();
    }

    @Override
    public void processStats(Event event) {

        Map<String, String> eventAttributes = event.getAttributes();
        if (event instanceof ModeChoiceEvent) {
            String mode = eventAttributes.get("mode");
            if (mode.equalsIgnoreCase("ride_hail")) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
                Id<Person> personId = modeChoiceEvent.getPersonId();
                rideHailWaiting.put(personId.toString(), event);
            }
        } else if (event instanceof PersonEntersVehicleEvent) {

            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent) event;
            Id<Person> personId = personEntersVehicleEvent.getPersonId();
            String _personId = personId.toString();

            // This rideHailVehicle check is put here again to remove the non rideHail vehicleId which were coming due the
            // another occurrence of modeChoice event because of replanning event.
            if (rideHailWaiting.containsKey(personId.toString()) && eventAttributes.get("vehicle").contains("rideHailVehicle")) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) rideHailWaiting.get(_personId);
                double difference = personEntersVehicleEvent.getTime() - modeChoiceEvent.getTime();
                processRideHailWaitingTimes(modeChoiceEvent, difference);

                // Building the RideHailWaitingIndividualStat List
                String __vehicleId = eventAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE);
                String __personId = eventAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);

                RideHailWaitingIndividualStat rideHailWaitingIndividualStat = new RideHailWaitingIndividualStat();
                rideHailWaitingIndividualStat.time = modeChoiceEvent.getTime();
                rideHailWaitingIndividualStat.personId = __personId;
                rideHailWaitingIndividualStat.vehicleId = __vehicleId;
                rideHailWaitingIndividualStat.waitingTime = difference;
                rideHailWaitingIndividualStatList.add(rideHailWaitingIndividualStat);


                // Remove the personId from the list of ModeChoiceEvent
                rideHailWaiting.remove(_personId);
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
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph(data.getSecond());
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        writeToCSV(event.getIteration(), data.getFirst());
        writeRideHailWaitingIndividualStatCSV(event.getIteration());
    }

    private void writeRideHailWaitingIndividualStatCSV(int iteration) {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration, "rideHailIndividualWaitingTimes.csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            String heading = "timeOfDayInSeconds,personId,rideHailVehicleId,waitingTimeInSeconds";

            out.write(heading);
            out.newLine();

            for (RideHailWaitingIndividualStat rideHailWaitingIndividualStat : rideHailWaitingIndividualStatList) {

                String line = rideHailWaitingIndividualStat.time + "," +
                        rideHailWaitingIndividualStat.personId + "," +
                        rideHailWaitingIndividualStat.vehicleId + "," +
                        rideHailWaitingIndividualStat.waitingTime;

                out.write(line);

                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processRideHailWaitingTimes(Event event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        waitingTime = waitingTime/60;

        List<Double> timeList = hoursTimesMap.get(hour);
        if (timeList == null) {
            timeList = new ArrayList<>();
        }
        timeList.add(waitingTime);
        this.waitTimeSum += waitingTime;
        this.rideHailCount++;
        hoursTimesMap.put(hour, timeList);
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph(double[][] dataset) {
        CategoryDataset categoryDataset = null;
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Time ", "", dataset);
        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName + ".png", true);
        CategoryPlot plot = chart.getCategoryPlot();

        // Legends
        List<String> legends = getLegends(getCategories());
        GraphUtils.plotLegendItems(plot, legends, dataset.getRowCount());

        // Writing graph to image file
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void writeToCSV(int iterationNumber, Map<Integer, Map<Double, Integer>> hourModeFrequency) {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv");
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
                        line = "60+," + (i + 1) + "," + line;
                    } else {
                        line = _category + "," + (i + 1) + "," + line;
                    }
                    out.write(line);
                    out.newLine();
                }
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Utility Methods
    private List<Double> getCategories() {

        List<Double> listOfBounds = new ArrayList<>();
        listOfBounds.add(5.0);
        listOfBounds.add(10.0);
        listOfBounds.add(20.0);
        listOfBounds.add(30.0);
        listOfBounds.add(60.0);
        listOfBounds.add(Double.MAX_VALUE);

        return listOfBounds;
    }

    private List<String> getLegends(List<Double> categories) {

        List<String> legends = new ArrayList<>();
        for (Double category : categories) {

            double legend = getRoundedCategoryUpperBound(category);
            if(legend > 60 )
                legends.add("60+");
            else{
                legends.add(category.intValue() + "min");
            }

        }
        //Collections.sort(legends);
        return legends;
    }

    private double getRoundedCategoryUpperBound(double category) {
        return Math.round(category * 100) / 100.0;
    }

    class RideHailWaitingIndividualStat {
        double time;
        String personId;
        String vehicleId;
        double waitingTime;
    }
}
