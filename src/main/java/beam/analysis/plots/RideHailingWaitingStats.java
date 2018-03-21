package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.jsoup.helper.StringUtil;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
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
    private static Set<String> timeSlots = new TreeSet<>();
    private static Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private static final String graphTitle = "Ride Hailing Waiting Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "Waiting Time";
    private static final String fileName = "RideHailingWaitingStats.png";
    private static HashMap<String, Event> rideHailingWaiting = new HashMap<>();
    private static int lastMax = 0;

    @Override
    public void processStats(Event event) {
        if (event instanceof ModeChoiceEvent && event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE).equalsIgnoreCase(GraphsStatsAgentSimEventsListener.RIDE_HAILING)) {
            ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
            rideHailingWaiting.put(modeChoiceEvent.getPersonId().toString(), event);
        } else if (event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) && rideHailingWaiting.containsKey(((PersonEntersVehicleEvent) event).getPersonId().toString())) {
            PersonEntersVehicleEvent personEntersVehicleEvent = (PersonEntersVehicleEvent) event;
            String id = personEntersVehicleEvent.getPersonId().toString();
            ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) rideHailingWaiting.get(id);
            double difference = event.getTime() - modeChoiceEvent.getTime();
            rideHailingWaiting.remove(id);
            processRideHailingWaitingTimes(modeChoiceEvent, difference);
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("just for no reason");
    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        timeSlots.clear();
        rideHailingWaiting.clear();
        RideHailingWaitingStats.lastMax = 0;
    }


    private void processRideHailingWaitingTimes(Event event, double time) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        String range = getTimeSlot(time);
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
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> timeRanges = new ArrayList<>();
        timeRanges.addAll(timeSlots);
        Collections.sort(timeRanges);
        GraphUtils.plotLegendItems(plot, timeRanges, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
        writeToCSV(iterationNumber);
    }


    /**
     * converts the given seconds to minutes and returns the range it lies in
     *
     * @param time seconds
     * @return
     */
    private static synchronized String getTimeSlot(double time) {
        time = time / 60;
        if (((int) time) > lastMax) {
            lastMax = (int) time;
        }
        if (time < 1) return "0-1 mins";
        else if (time < 2) return "1-2 mins";
        else if (time < 4) return "2-4 mins";
        else return "4-"+lastMax+" mins";
    }


    private void writeToCSV(int iterationNumber) throws IOException {
        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, "RideHailWaitingStats.csv");
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));
            String heading = "Hour," + StringUtil.join(timeSlots, ",").replace(" mins","_min").replaceAll("[0-9]*-","");
            out.write(heading);
            out.newLine();
            for (int i = 0; i < 24; i++) {
                out.write("" + (i+1));
                Map<String, Integer> innerMap = hourModeFrequency.get(i);
                if (innerMap == null) {
                    for (int j = 0; j < timeSlots.size(); j++)
                        out.write(",0");
                    out.newLine();
                    continue;
                }
                for (String slot : timeSlots) {
                    String frequency = innerMap.get(slot) == null ? "0" : innerMap.get(slot).toString();
                    out.write("," + frequency);
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
}
