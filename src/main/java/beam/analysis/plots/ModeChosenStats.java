package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;

public class ModeChosenStats implements IGraphStats, MetricsSupport {
    private static final String graphTitle = "Mode Choice Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "mode_choice.png";
    private static Set<String> modesChosen = new TreeSet<>();
    private static Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private static Set<String> iterationTypeSet = new HashSet();
    private static Map<Integer, Map<String, Integer>> modeChoiceInIteration = new HashMap<>();
    private Logger log = LoggerFactory.getLogger(this.getClass());



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

        updateModeChoiceInIteration(event.getIteration());
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) {

    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        modesChosen.clear();
    }

    public int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour) {
        double[] modeOccurrencePerHour = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour);
        return (int) Arrays.stream(modeOccurrencePerHour).sum();
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

    //    accumulating data for each iteration
    public void updateModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Integer> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Integer> iterationHourData = hourModeFrequency.get(hour);
            Set<String> iterationModes = iterationHourData.keySet();
            for (String iterationMode : iterationModes) {
                Integer freq = iterationHourData.get(iterationMode);
                Integer iterationFrequency = totalModeChoice.get(iterationMode);
                if (iterationFrequency == null) {
                    totalModeChoice.put(iterationMode, freq);
                } else {
                    totalModeChoice.put(iterationMode, freq + iterationFrequency);
                }

            }
        }
        iterationTypeSet.add("it." + iteration);
        modeChoiceInIteration.put(iteration, totalModeChoice);

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
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modesChosen);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName);
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

//    event hits at the end of the running scenario
    public void notifyShutdown(ShutdownEvent event) throws Exception {
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename("modeChoice.png");
        CategoryDataset dataset = buildModeChoiceDatasetForGraph();
        if (dataset != null)
            createRootModeChoosenGraph(dataset, fileName);
        writeToRootCSV();
    }


//    dataset for root graph
    private CategoryDataset buildModeChoiceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildTotalModeChoiceDataset();

        if (dataset != null) {
            categoryDataset = createCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    public CategoryDataset createCategoryDataset(String columnKeyPrefix, double[][] data) {

        DefaultCategoryDataset result = new DefaultCategoryDataset();
        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue(data[r][c], rowKey, columnKey);
            }
        }
        return result;
    }

    private double[][] buildTotalModeChoiceDataset() {

        List<Integer> iterationList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(modeChoiceInIteration.keySet());
        List<String> modeChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);
        if (iterationList.size() == 0)
            return null;
        Integer maxIteration = iterationList.get(iterationList.size() - 1);
        double[][] dataset = new double[modesChosen.size()][];
        for (int i = 0; i < modeChosenList.size(); i++) {
            String mode = modeChosenList.get(i);
            dataset[i] = getDataPerOccurrenceAgainstModeChoice(mode, maxIteration);
        }
        return dataset;
    }


    private double[] getDataPerOccurrenceAgainstModeChoice(String mode, int maxIteration) {
        double[] occurrenceAgainstModeChoice = new double[maxIteration + 1];
        int index = 0;
        for (int iteration = 0; iteration <= maxIteration; iteration++) {
            Map<String, Integer> iterationData = modeChoiceInIteration.get(iteration);
            if (iterationData != null) {
                occurrenceAgainstModeChoice[index] = iterationData.get(mode) == null ? 0 : iterationData.get(mode);
            } else {
                occurrenceAgainstModeChoice[index] = 0;
            }
            index = index + 1;
        }
        return occurrenceAgainstModeChoice;
    }


//    generating graph in root directory
    private void createRootModeChoosenGraph(CategoryDataset dataset, String fileName) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, "Iteration", "# mode choosen", fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(modesChosen);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    // csv for root modeChoice.png
    private void writeToRootCSV() {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("modeChoice.csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = modesChosen;

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            int max = modeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
                StringBuilder builder = new StringBuilder(iteration +"");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append(",").append(modeCount.get(mode));
                        } else {
                            builder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        builder.append(",0");
                    }
                }
                out.write(builder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

}
