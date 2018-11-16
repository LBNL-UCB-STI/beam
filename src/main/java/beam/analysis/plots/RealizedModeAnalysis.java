package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.sim.OutputDataDescription;
import beam.sim.metrics.MetricsSupport;
import beam.utils.OutputDataDescriptor;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.utils.collections.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;

public class RealizedModeAnalysis implements GraphAnalysis, MetricsSupport , OutputDataDescriptor {


    private static final String graphTitle = "Realized Mode Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "realizedMode";
    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private HashSet<String> personIdList = new HashSet<>();
    private Map<String, Stack<ModeHour>> hourPerson = new HashMap<>();
    private HashSet<String> recentPersonIdRemoveList = new HashSet<>();
    private Map<Integer, Map<String, Integer>> realizedModeChoiceInIteration = new HashMap<>();
    private Set<String> iterationTypeSet = new HashSet<>();
    private Set<String> cumulativeMode = new TreeSet<>();

    private Logger log = LoggerFactory.getLogger(this.getClass());
    private final boolean writeGraph;
    private final StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation;

    public RealizedModeAnalysis(StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation, boolean writeGraph) {
        this.statComputation = statComputation;
        this.writeGraph = writeGraph;
    }

    @Override
    public List<OutputDataDescription> getOutputDataDescriptions() {
        String outputFilePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(fileName + ".csv");
        String outputDirPath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath();
        String relativePath = outputFilePath.replace(outputDirPath, "");
        List<OutputDataDescription> list = new ArrayList<>();
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "iterations", "iteration number"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "car", "Car chosen as travel mode"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "drive_transit", "Drive to transit chosen as travel mode"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "other", "Other modes of travel chosen"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "ride_hail", "Ride Hail chosen as travel mode"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "walk", "Walk chosen as travel mode"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "walk_transit", "Walk to transit chosen as travel mode"));
        return list;
    }

    public static class RealizedModesStatsComputation implements StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> {

        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {

            List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
            List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());
            if (0 == hoursList.size())
                return null;
            int maxHour = hoursList.get(hoursList.size() - 1);
            double[][] dataset = new double[stat.getSecond().size()][maxHour + 1];
            for (int i = 0; i < modesChosenList.size(); i++) {
                String modeChosen = modesChosenList.get(i);
                dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour, stat.getFirst());
            }
            return dataset;
        }

        private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Integer>> stat) {
            double[] modeOccurrencePerHour = new double[maxHour + 1];
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<String, Integer> hourData = stat.get(hour);
                if (hourData != null) {
                    modeOccurrencePerHour[hour] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                } else {
                    modeOccurrencePerHour[hour] = 0;
                }
            }
            return modeOccurrencePerHour;
        }
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof ReplanningEvent || event.getEventType().equalsIgnoreCase(ReplanningEvent.EVENT_TYPE) ||
                event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE))
            processRealizedMode(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {

        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "aggregated-mode-choice");
        hourModeFrequency.values().stream().filter(Objects::nonNull).flatMap(x -> x.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b))
                .forEach((mode, count) -> countOccurrenceJava(mode, count, ShortLevel(), tags));

        updateRealizedModeChoiceInIteration(event.getIteration());
        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null && writeGraph)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        writeToCSV(event);
    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        personIdList.clear();
        hourPerson.clear();
        recentPersonIdRemoveList.clear();
    }

    // The modeChoice events for same person as of replanning event will be excluded in the form of CRC, CRCRC, CRCRCRC so on.
    private void processRealizedMode(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        Map<String, String> eventAttributes = event.getAttributes();
        if (ModeChoiceEvent.EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            String mode = eventAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE);
            String personId = eventAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID);
            Map<String, String> tags = new HashMap<>();
            tags.put("stats-type", "mode-choice");
            tags.put("hour", "" + (hour + 1));

            countOccurrenceJava(mode, 1, ShortLevel(), tags);
            if (personIdList.contains(personId)) {
                personIdList.remove(personId);
                recentPersonIdRemoveList.add(personId);
                return;
            }

            recentPersonIdRemoveList.remove(personId);

            Integer frequency = 1;
            if (hourData != null) {
                frequency = hourData.getOrDefault(mode, 0);
                frequency++;
            } else {
                hourData = new HashMap<>();
            }

            hourData.put(mode, frequency);

            ModeHour modeHour = new ModeHour(mode, hour);
            Stack<ModeHour> modeHours = hourPerson.get(personId);
            if (modeHours == null) {
                modeHours = new Stack<>();
            }
            modeHours.push(modeHour);
            hourPerson.put(personId, modeHours);

            hourModeFrequency.put(hour, hourData);

        }
        if (ReplanningEvent.EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            if (eventAttributes != null) {
                String person = eventAttributes.get(ReplanningEvent.ATTRIBUTE_PERSON);
                personIdList.add(person);

                Stack<ModeHour> modeHours = hourPerson.get(person);

                if (modeHours != null && modeHours.size() > 0 && !recentPersonIdRemoveList.contains(person)) {
                    ModeHour modeHour = modeHours.pop();
                    hourPerson.put(person, modeHours);

                    Integer replanning = 1;
                    if (hourData != null) {
                        replanning = hourData.get("others");
                        if (replanning != null) {
                            replanning++;
                        } else {
                            replanning = 1;
                        }
                    } else {
                        hourData = new HashMap<>();
                    }

                    hourData.put("others", replanning);
                    Map<String, Integer> hourMode = hourModeFrequency.get(modeHour.getHour());
                    if (hourMode != null) {
                        Integer frequency = hourMode.get(modeHour.getMode());
                        if (frequency != null) {
                            frequency--;
                            hourMode.put(modeHour.getMode(), frequency);
                        }
                    }
                }

            }
        }
        hourModeFrequency.put(hour, hourData);
    }

    //    accumulating data for each iteration
    public void updateRealizedModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Integer> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Integer> iterationHourData = hourModeFrequency.get(hour);
            if (iterationHourData != null) {
                Set<String> iterationModes = iterationHourData.keySet();
                for (String iterationMode : iterationModes) {
                    Integer freq = iterationHourData.get(iterationMode);
                    totalModeChoice.merge(iterationMode, freq, (a, b) -> b + a);
                }
            }
        }
        iterationTypeSet.add("it." + iteration);
        realizedModeChoiceInIteration.put(iteration, totalModeChoice);

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
        List<String> modesChosenList = new ArrayList<>(getModesChosen());
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    //This is used for removing columns if all entries is 0
    private Set<String> getModesChosen() {

        Set<String> modes = new TreeSet<>();
        Map<String, Integer> modeCountBucket = new HashMap<>();
        hourModeFrequency.keySet().stream().filter(hour -> hourModeFrequency.get(hour) != null).forEach(hour -> hourModeFrequency.get(hour).keySet().
                forEach(mode -> {
                    Integer count = modeCountBucket.get(mode);
                    Map<String, Integer> modeFrequency = hourModeFrequency.get(hour);
                    if (modeFrequency != null) {
                        if (count != null) {
                            modeCountBucket.put(mode, count + modeFrequency.get(mode));
                        } else {
                            modeCountBucket.put(mode, modeFrequency.get(mode));
                        }
                    }
                }));

        modeCountBucket.keySet().forEach(mode -> {
            if (modeCountBucket.get(mode) != null && modeCountBucket.get(mode) != 0) {
                modes.add(mode);
            }
        });
        cumulativeMode.addAll(modes);
        return modes;
    }

    public void notifyShutdown(ShutdownEvent event) throws Exception {
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename("realizedModeChoice.png");
        CategoryDataset dataset = buildRealizedModeChoiceDatasetForGraph();
        if (dataset != null && writeGraph)
            createRootRealizedModeChoosenGraph(dataset, fileName);
        writeToRootCSV();
    }


    // dataset for root graph
    private CategoryDataset buildRealizedModeChoiceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildTotalRealizedModeChoiceDataset();

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

    private double[][] buildTotalRealizedModeChoiceDataset() {
        return statComputation.compute(new Tuple<>(realizedModeChoiceInIteration, cumulativeMode));
    }

    // generating graph in root directory
    private void createRootRealizedModeChoosenGraph(CategoryDataset dataset, String fileName) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, "Iteration", "# mode choosen", fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(cumulativeMode);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }


    double[][] buildModesFrequencyDataset() {
        Set<String> modeChoosen = getModesChosen();
        return statComputation.compute(new Tuple<>(hourModeFrequency, modeChoosen));
    }

    private void writeToCSV(IterationEndsEvent event) {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), fileName + ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = getModesChosen();

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("hours," + heading);
            out.newLine();

            int max = hourModeFrequency.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int hour = 0; hour <= max; hour++) {
                Map<String, Integer> modeCount = hourModeFrequency.get(hour);
                StringBuilder builder = new StringBuilder(hour + 1 + "");
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

    // csv for root graph
    public void writeToRootCSV() {
        String fileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("realizedModeChoice.csv");
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(new File(fileName)));
            Set<String> modes = cumulativeMode;
            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            int max = realizedModeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCountIteration = realizedModeChoiceInIteration.get(iteration);
                StringBuilder stringBuilder = new StringBuilder(iteration + "");
                if (modeCountIteration != null) {
                    for (String mode : modes) {
                        if (modeCountIteration.get(mode) != null) {
                            stringBuilder.append(",").append(modeCountIteration.get(mode));
                        } else {
                            stringBuilder.append(",0");
                        }
                    }
                } else {
                    for (String ignored : modes) {
                        stringBuilder.append(",0");
                    }
                }
                out.write(stringBuilder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("error in generating CSV", e);
        }
    }


    public class ModeHour {
        private String mode;
        private Integer hour;

        public ModeHour(String mode, Integer hour) {
            this.mode = mode;
            this.hour = hour;
        }

        public String getMode() {
            return mode;
        }

        public Integer getHour() {
            return hour;
        }

        @Override
        public String toString() {
            return "[Mode: " + mode + ", Hour: " + hour + "]";
        }

        @Override
        public boolean equals(Object o) {

            if (o == this) return true;
            if (!(o instanceof ModeHour)) {
                return false;
            }

            ModeHour modeHour = (ModeHour) o;

            return modeHour.mode.equals(mode) &&
                    modeHour.hour.equals(hour);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + mode.hashCode();
            result = 31 * result + hour.hashCode();
            return result;
        }
    }
}

