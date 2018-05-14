package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.jsoup.helper.StringUtil;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

/**
 * @author abid
 */
public class RideHailingWaitingStats implements IGraphStats {

    private static final String graphTitle = "Ride Hail Waiting Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Waiting Time (min)";
    private static final String fileName = "RideHailWaitingStats";

    private int lastMax = 0;
    private double lastMaximumTime = 0;
    private double NUMBER_OF_CATEGORIES = 6.0;

    private HashMap<String, Event> rideHailingWaiting = new HashMap<>();
    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private Set<String> timeSlots = new TreeSet<>();
    private Map<Integer, List<Double>> hoursTimesMap = new HashMap<>();
    private List<Double> listOfBounds = new ArrayList<>();

    @Override
    public void processStats(Event event) {

        if (event instanceof ModeChoiceEvent){

            String mode = event.getAttributes().get("mode");
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

                // Remove the personId from the list of ModeChoiceEvent
                rideHailingWaiting.remove(_personId);
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        calculateHourlyData();
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("Not implemented");
    }

    @Override
    public void resetStats() {
        lastMax = 0;
        lastMaximumTime = 0;

        hourModeFrequency.clear();
        timeSlots.clear();
        rideHailingWaiting.clear();
        hoursTimesMap.clear();
    }


    private void processRideHailingWaitingTimes(Event event, double waitingTime) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());

        //waitingTime = Math.ceil(waitingTime / 60);
        waitingTime = waitingTime/60;

        if (waitingTime > lastMaximumTime) {
            lastMaximumTime = waitingTime;
        }

        List<Double> timeList = hoursTimesMap.get(hour);
        if (timeList == null) {
            timeList = new ArrayList<>();
        }
        timeList.add(waitingTime);
        hoursTimesMap.put(hour, timeList);
    }

    private double[] getHoursDataPerTimeRange(String timeRange, int maxHour) {
        double[] timeRangeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourData = hourModeFrequency.get(hour);
            timeRangeOccurrencePerHour[index] = (hourData == null || hourData.get(timeRange) == null) ? 0 : hourData.get(timeRange);
            index++;
        }
        return timeRangeOccurrencePerHour;
    }

    private double[][] buildModesFrequencyDataset() {
        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
        List<String> timeSlotList = GraphsStatsAgentSimEventsListener.getSortedStringList(timeSlots);
        if (hoursList.isEmpty())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[timeSlots.size()][maxHour + 1];
        for (int i = 0; i < timeSlotList.size(); i++) {
            String modeChosen = timeSlotList.get(i);
            dataset[i] = getHoursDataPerTimeRange(modeChosen, maxHour);
        }
        return dataset;
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildModesFrequencyDataset();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Time ", "", dataset);
        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName + ".png", legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> timeRanges = new ArrayList<>();
        timeRanges.addAll(timeSlots);
        Collections.sort(timeRanges);
        GraphUtils.plotLegendItems(plot, timeRanges, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        writeToCSV(iterationNumber);
    }

    /**
     * Recursive function that will add the upper and lower bounds to the list
     *
     */
    private void getBounds() {
        /*DecimalFormat df = new DecimalFormat();
        df.setMaximumFractionDigits(2);*/
        /*if ((upperBound - bound) <= 0) {
            listOfBounds.add(upperBound);
            listOfBounds.add(0.0);
        } else if (bound < upperBound) {
            listOfBounds.add(bound);
            getBounds(bound + , upperBound);
        }*/
        double upperBound = lastMaximumTime;
        double bound = (lastMaximumTime / NUMBER_OF_CATEGORIES);

        listOfBounds.add(0.0);
        for(double x = bound; x <= upperBound; x += bound){
            listOfBounds.add(x);
        }
    }

    private void writeToCSV(int iterationNumber) throws IOException {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv");
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));
            String heading = "WaitingTime\\Hour";
            for (int hours = 1; hours <= 24; hours++) {
                heading += "," + hours;
            }
            out.write(heading);
            out.newLine();

            for (String slot : timeSlots) {
                out.write(slot);
                String line = "";
                for (int i = 0; i < 24; i++) {
                    Map<String, Integer> innerMap = hourModeFrequency.get(i);
                    line = (innerMap == null || innerMap.get(slot) == null) ? ",0" : "," + innerMap.get(slot);
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
    private synchronized void calculateHourlyData() {
        getBounds();
        Collections.sort(listOfBounds);
        Set<Integer> hours = hoursTimesMap.keySet();

        for (Integer hour : hours) {
            List<Double> listTimes = hoursTimesMap.get(hour);
            for (double time : listTimes) {
                String range = getSlot(time);
                if(range == null){
                    System.out.println("range is null");
                }else{
                    timeSlots.add(range);
                    Map<String, Integer> hourData = hourModeFrequency.get(hour);
                    Integer frequency = 1;
                    if (hourData != null) {
                        frequency = hourData.get(range);
                        frequency = (frequency == null) ? 1 : frequency + 1;
                    } else {
                        hourData = new HashMap<>();
                    }
                    hourData.put(range, frequency);
                    hourModeFrequency.put(hour, hourData);
                }
            }
        }
    }

    /**
     * Returns the category in which the current time lies
     *
     * @param time given time
     * @return name of the category e.g. "0.0-2.0 mins"
     */
    private String getSlot(double time) {
        int i = 1;
        while (i < listOfBounds.size()) {
            double range = listOfBounds.get(i);
            if (time <= range) {

                range = Math.round(range*100)/100.0;

                return range + "_min";
                //return listOfBounds.get(i - 1) + "-" + range + " mins ";
            }
            i++;
        }
        return null;
    }
}
