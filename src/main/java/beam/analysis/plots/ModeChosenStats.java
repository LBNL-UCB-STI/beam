package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.sim.metrics.MetricsSupport;
import beam.analysis.via.CSVWriter;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;public class ModeChosenStats implements IGraphStats, MetricsSupport{
    private static Set<String> modesChosen = new TreeSet<>();
    private static Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private static final String GRAPH_TITLE = "Mode Choice Histogram";
    private static final String X_AXIS_TITLE = "Hour";
    private static final String Y_AXIS_TITLE = "# mode chosen";
    private static final String MODE_CHOICE_GRAPH_FILE_NAME = "mode_choice.png";
    public static final String MODE_CHOICE_CSV_FILE_NAME = "modeChoice.csv";

    @Override
    public void processStats(Event event) {
        processModeChoice(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "aggregated-mode-choice");
        hourModeFrequency.values().stream().flatMap(x -> x.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b))
                .forEach((mode, count) -> countOccurrenceJava(mode, count, ShortLevel(), tags));

        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null) {
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
            writeModeShareCSV();
        }
    }

    public void writeModeShareCSV() {

        String SEPERATOR=",";

        CSVWriter csvWriter = new CSVWriter(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(MODE_CHOICE_CSV_FILE_NAME));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();

        Map<String,Double> modeShareMap = getModeShareMap();

        try{
            bufferedWriter.append("mode");
            bufferedWriter.append(SEPERATOR);
            bufferedWriter.append("share");
            bufferedWriter.append("\n");

            for (Map.Entry<String, Double> entry : modeShareMap.entrySet()) {
                bufferedWriter.append(entry.getKey());
                bufferedWriter.append(SEPERATOR);
                bufferedWriter.append(Double.toString(entry.getValue()));
                bufferedWriter.append("\n");
            }
            bufferedWriter.flush();
            bufferedWriter.close();
        }catch (IOException e){
            e.printStackTrace();
        }


    }

    public Map<String, Double>  getModeShareMap() {
        List<String> modesChosenList = new ArrayList<>(modesChosen);
        Map<String, Double> modeShareMap = new HashMap<>();

        Collections.sort(modesChosenList);
        double[][] dataset = buildModesFrequencyDataset();
        double totalCounts = 0.0;
        double[] result = new double[modesChosenList.size()];
        for (int i = 0; i < modesChosenList.size(); i++) {
            double modeCounts = 0.0;
            if (dataset != null) {
                double[] modeOccurrencePerHour = dataset[i];
                for (double aModeOccurrencePerHour : modeOccurrencePerHour) {
                    modeCounts += aModeOccurrencePerHour;
                }
            }
            result[i] = modeCounts;
            totalCounts += modeCounts;
        }
        for (int i = 0; i < result.length; i++) {
            modeShareMap.put(modesChosenList.get(i), result[i] / totalCounts);
        }
     return modeShareMap;
    }



    @Override
    public void createGraph(IterationEndsEvent event, String graphType) {

    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        modesChosen.clear();
    }

    public int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour){
        double [] modeOccurrencePerHour = getHoursDataPerOccurrenceAgainstMode(modeChosen,maxHour);

        return (int)Arrays.stream(modeOccurrencePerHour).sum();
    }

    public int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour, int hour) {
        double[] modeOccurrencePerHour = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour);
        return (int) Math.ceil(modeOccurrencePerHour[hour]);
    }

    public List<Integer> getSortedHourModeFrequencyList() {
        return GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
    }

    private void processModeChoice(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        String mode = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE);
        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "mode-choice");
        tags.put("hour", "" + (hour + 1));
        countOccurrenceJava(mode, 1, ShortLevel(), tags);
        modesChosen.add(mode);
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        Integer frequency = 1;
        if (hourData != null) {
            frequency = hourData.get(mode);
            if (frequency != null) {
                frequency++;
            } else {
                frequency = 1;
            }
        } else {
            hourData = new HashMap<>();
        }
        hourData.put(mode, frequency);
        hourModeFrequency.put(hour, hourData);
    }

    private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourData = hourModeFrequency.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private double[][] buildModesFrequencyDataset() {

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);
        if (0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[modesChosen.size()][maxHour + 1];
        for (int i = 0; i < modesChosenList.size(); i++) {
            String modeChosen = modesChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour);
        }
        return dataset;
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildModesFrequencyDataset();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);

        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, GRAPH_TITLE, X_AXIS_TITLE, Y_AXIS_TITLE, MODE_CHOICE_GRAPH_FILE_NAME,true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modesChosen);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, MODE_CHOICE_GRAPH_FILE_NAME);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

}
