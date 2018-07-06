package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * @author abid
 */
public class RideHailingWaitingStats implements IGraphStats {

    class RideHailWaitingIndividualStat{
        double time;
        String personId;
        String vehicleId;
        double waitingTime;
    }

    List<RideHailWaitingIndividualStat> rideHailWaitingIndividualStatList = new ArrayList<>();

    private static final String graphTitle = "Ride Hail Waiting Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Waiting Time (frequencies)";
    private static final String fileName = "RideHailWaitingStats";

    private double lastMaximumTime = 0;
    private double NUMBER_OF_CATEGORIES = 6.0;

    private Map<String, Event> rideHailingWaiting = new HashMap<>();

    private Map<Integer, List<Double>> hoursTimesMap = new HashMap<>();

    private double waitTimeSum = 0;   //sum of all wait times experienced by customers
    private int rideHailCount = 0;   //later used to calculate average wait time experienced by customers


    @Override
    public void resetStats() {
        lastMaximumTime = 0;
        waitTimeSum = 0;
        rideHailCount = 0;
        rideHailingWaiting.clear();
        hoursTimesMap.clear();
        rideHailWaitingIndividualStatList.clear();
    }

    @Override
    public void processStats(Event event) {

        Map<String, String> eventAttributes = event.getAttributes();
        if (event instanceof ModeChoiceEvent){
            String mode = eventAttributes.get("mode");
            if(mode.equalsIgnoreCase("ride_hailing")) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
                Id<Person> personId = modeChoiceEvent.getPersonId();
                rideHailingWaiting.put(personId.toString(), event);
            }
        } else if(event instanceof PersonEntersVehicleEvent) {

            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent)event;
            Id<Person> personId = personEntersVehicleEvent.getPersonId();
            String _personId = personId.toString();

            if(rideHailingWaiting.containsKey(personId.toString())) {

                ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) rideHailingWaiting.get(_personId);
                double difference = personEntersVehicleEvent.getTime() - modeChoiceEvent.getTime();
                processRideHailingWaitingTimes(modeChoiceEvent, difference);

                // Building the RideHailWaitingIndividualStat List
                String __vehicleId = personEntersVehicleEvent.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE);
                String __personId = personEntersVehicleEvent.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);

                RideHailWaitingIndividualStat rideHailWaitingIndividualStat = new RideHailWaitingIndividualStat();
                rideHailWaitingIndividualStat.time = modeChoiceEvent.getTime();
                rideHailWaitingIndividualStat.personId = __personId;
                rideHailWaitingIndividualStat.vehicleId = __vehicleId;
                rideHailWaitingIndividualStat.waitingTime = difference;
                rideHailWaitingIndividualStatList.add(rideHailWaitingIndividualStat);


                // Remove the personId from the list of ModeChoiceEvent
                rideHailingWaiting.remove(_personId);
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(event.getIteration());
        if (model == null)
            model = new RideHailDistanceRowModel();
        model.setRideHailWaitingTimeSum(this.waitTimeSum);
        model.setTotalRideHailCount(this.rideHailCount);
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);
        List<Double> listOfBounds = getCategories();
        Map<Integer, Map<Double, Integer>> hourModeFrequency = calculateHourlyData(hoursTimesMap, listOfBounds);
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph(hourModeFrequency);
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        writeToCSV(event.getIteration(), hourModeFrequency);
        writeRideHailWaitingIndividualStatCSV(event.getIteration());
    }

    private void writeRideHailWaitingIndividualStatCSV(int iteration) throws IOException{

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iteration,"rideHailIndividualWaitingTimes.csv");
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));
            String heading = "timeOfDayInSeconds,personId,rideHailVehicleId,waitingTimeInSeconds";

            out.write(heading);
            out.newLine();

            for (RideHailWaitingIndividualStat rideHailWaitingIndividualStat : rideHailWaitingIndividualStatList){


                String line = rideHailWaitingIndividualStat.time + "," +
                        rideHailWaitingIndividualStat.personId + "," +
                        rideHailWaitingIndividualStat.vehicleId + "," +
                        rideHailWaitingIndividualStat.waitingTime;

                out.write(line);

                out.newLine();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("Not implemented");
    }



    private void processRideHailingWaitingTimes(Event event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        //waitingTime = waitingTime/60;

        if (waitingTime > lastMaximumTime) {
            lastMaximumTime = waitingTime;
        }

        List<Double> timeList = hoursTimesMap.get(hour);
        if (timeList == null) {
            timeList = new ArrayList<>();
        }
        timeList.add(waitingTime);
        this.waitTimeSum += waitingTime;
        this.rideHailCount++;
        hoursTimesMap.put(hour, timeList);
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

    private double[][] buildModesFrequencyDataset(Map<Integer, Map<Double, Integer>> hourModeFrequency) {

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());

        if (hoursList.isEmpty())
            return null;

        int maxHour = hoursList.get(hoursList.size() - 1);

        List<Double> categories = getCategories();
        double[][] dataset = new double[categories.size()][maxHour + 1];

        for (int i = 0; i < categories.size(); i++) {
            dataset[i] = getHoursDataPerTimeRange(categories.get(i), maxHour, hourModeFrequency);
        }
        return dataset;
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph(Map<Integer, Map<Double, Integer>> hourModeFrequency) {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildModesFrequencyDataset(hourModeFrequency);
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Time ", "", dataset);
        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {

        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName + ".png", legend);
        CategoryPlot plot = chart.getCategoryPlot();

        // Legends
        List<String> legends = getLegends(getCategories());
        GraphUtils.plotLegendItems(plot, legends, dataset.getRowCount());

        // Writing graph to image file
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }



    private void writeToCSV(int iterationNumber, Map<Integer, Map<Double, Integer>> hourModeFrequency) throws IOException {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv");
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));
            String heading = "WaitingTime(sec)\\Hour";
            for (int hours = 1; hours <= 24; hours++) {
                heading += "," + hours;
            }
            out.write(heading);
            out.newLine();

            List<Double> categories = getCategories();


            for (int j = 0; j < categories.size(); j++){

                Double category = categories.get(j);
                Double _category = getRoundedCategoryUpperBound(category);
                out.write(_category + "");
                String line = "";
                for (int i = 0; i < 24; i++) {
                    Map<Double, Integer> innerMap = hourModeFrequency.get(i);
                    line = (innerMap == null || innerMap.get(category) == null) ? ",0" : "," + innerMap.get(category);
                    out.write(line);
                }
                out.newLine();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Calculate the data and populate the dataset i.e. "hourModeFrequency"
     */
    private synchronized Map<Integer, Map<Double, Integer>>
        calculateHourlyData(Map<Integer, List<Double>> hoursTimesMap, List<Double> categories) {

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


    // Utility Methods
    private List<Double> getCategories() {

        List<Double> listOfBounds = new ArrayList<>();

        double upperBound = lastMaximumTime;
        double bound = (lastMaximumTime / NUMBER_OF_CATEGORIES);

        //listOfBounds.add(0.0);

        for(double x = bound; x < upperBound; x += bound){
            listOfBounds.add(x);
        }

        if(!listOfBounds.isEmpty()) {
            listOfBounds.set(listOfBounds.size() - 1, lastMaximumTime);
            Collections.sort(listOfBounds);
        }

        return listOfBounds;
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

    private List<String> getLegends(List<Double> categories){

        List<String> legends = new ArrayList<>();
        for(int i = 0; i<categories.size(); i++){

            Double category = categories.get(i);
            double legend = getRoundedCategoryUpperBound(category);
            legends.add( legend + "_sec");
        }
        //Collections.sort(legends);
        return legends;
    }

    private double getRoundedCategoryUpperBound(double category){
        return Math.round(category * 100)/100.0;
    }
}
