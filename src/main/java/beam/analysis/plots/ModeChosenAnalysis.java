package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.via.CSVWriter;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.utils.collections.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;
import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

public class ModeChosenAnalysis implements GraphAnalysis, MetricsSupport {
    private static final String graphTitle = "Mode Choice Histogram";
    private static final String graphTitleBenchmark = "Reference Mode Choice Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "mode_choice";

    private final Set<String> iterationTypeSet = new HashSet<>();
    private final Map<Integer, Map<String, Integer>> modeChoiceInIteration = new HashMap<>();
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final Set<String> modesChosen = new TreeSet<>();
    private final Set<String> cumulativeModeChosenForModeChoice = new TreeSet<>();
    private final Set<String> cumulativeModeChosenForReference = new TreeSet<>();
    private final Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private final Map<String, Double> benchMarkData;

    private final StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation;

    public static class ModeChosenComputation implements StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> {

        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
            if (hoursList.isEmpty()) {
                return null;
            }
            final int maxHour = hoursList.get(hoursList.size() - 1);

            List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());

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
                Map<String, Integer> hourData = stat.getOrDefault(hour, Collections.emptyMap());
                modeOccurrencePerHour[hour] = defaultIfNull(hourData.get(modeChosen), 0);
            }
            return modeOccurrencePerHour;
        }
    }

    public ModeChosenAnalysis(StatsComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation, BeamConfig beamConfig) {
        final String benchmarkFileLoc = beamConfig.beam().calibration().mode().benchmarkFileLoc();
        this.statComputation = statComputation;
        benchMarkData = benchmarkCsvLoader(benchmarkFileLoc);
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE))
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

        createModeChosenCSV(hourModeFrequency, event.getIteration());
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename("modeChoice.png");
        CategoryDataset dataset = buildModeChoiceDatasetForGraph();
        if (dataset != null) {
            createGraphInRootDirectory(dataset, graphTitle, fileName, "# mode choosen", cumulativeModeChosenForModeChoice);
        }
        writeToRootCSV();

        fileName = outputDirectoryHierarchy.getOutputFilename("reference_modeChoice.png");
        cumulativeModeChosenForReference.addAll(benchMarkData.keySet());
        CategoryDataset referenceDataset = buildModeChoiceReferenceDatasetForGraph();
        if (referenceDataset != null) {
            createGraphInRootDirectory(referenceDataset, graphTitleBenchmark, fileName, "# mode choosen(Percent)", cumulativeModeChosenForReference);
        }
        writeToRootCSVForReference();
    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        modesChosen.clear();
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
        cumulativeModeChosenForModeChoice.add(mode);
        cumulativeModeChosenForReference.add(mode);
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        Integer frequency = 1;
        if (hourData != null) {
            frequency = hourData.getOrDefault(mode, 0);
            frequency++;
        } else {
            hourData = new HashMap<>();
        }
        hourData.put(mode, frequency);
        hourModeFrequency.put(hour, hourData);
    }

    //    accumulating data for each iteration
    private void updateModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Integer> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Integer> iterationHourData = hourModeFrequency.get(hour);
            Set<String> iterationModes = iterationHourData.keySet();
            for (String iterationMode : iterationModes) {
                Integer freq = iterationHourData.get(iterationMode);
                Integer iterationFrequency = defaultIfNull(totalModeChoice.get(iterationMode), 0);
                totalModeChoice.put(iterationMode, freq + iterationFrequency);
            }
        }
        iterationTypeSet.add("it." + iteration);
        modeChoiceInIteration.put(iteration, totalModeChoice);
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = compute();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);

        return categoryDataset;
    }

    double[][] compute() {
        return statComputation.compute(new Tuple<>(hourModeFrequency, modesChosen));
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, true);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modesChosen);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    private void createModeChosenCSV(Map<Integer, Map<String, Integer>> hourModeChosen, int iterationNumber) {

        final String separator = ",";

        CSVWriter csvWriter = new CSVWriter(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv"));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();

        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeChosen.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);

        int maxHour = hours.get(hours.size() - 1);
        try {
            bufferedWriter.append("Modes");
            bufferedWriter.append(separator);
            for (int j = 0; j <= maxHour; j++) {
                bufferedWriter.append("Bin_")
                        .append(String.valueOf(j))
                        .append(separator);
            }
            bufferedWriter.append("\n");

            for (String modeChosen : modesFuelList) {

                bufferedWriter.append(modeChosen).append(separator);

                for (int j = 0; j <= maxHour; j++) {
                    Map<String, Integer> modesData = hourModeChosen.get(j);

                    String modeHourValue = "0";

                    if (modesData != null && modesData.get(modeChosen) != null) {
                        modeHourValue = modesData.get(modeChosen).toString();
                    }

                    bufferedWriter.append(modeHourValue).append(separator);
                }
                bufferedWriter.append("\n");
            }
            bufferedWriter.flush();
            csvWriter.closeFile();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    //    dataset for root graph
    private CategoryDataset buildModeChoiceDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(modeChoiceInIteration, cumulativeModeChosenForModeChoice));

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

    //    dataset for root graph
    private CategoryDataset buildModeChoiceReferenceDatasetForGraph() throws IOException {
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(modeChoiceInIteration, cumulativeModeChosenForReference));

        if (dataset != null) {
            categoryDataset = createReferenceCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    // The data is converted into average and compared with the data of benchmark.
    private CategoryDataset createReferenceCategoryDataset(String columnKeyPrefix, double[][] data) {
      DefaultCategoryDataset result = new DefaultCategoryDataset();
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(benchMarkData.keySet());
        double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
        for (int i = 0; i < modesChosenList.size(); i++) {
            String rowKey = String.valueOf(i + 1);
            result.addValue((benchMarkData.get(modesChosenList.get(i)) * 100) / sum, rowKey, "benchmark");
        }
        int max = 0;
        for (double[] aData : data) {
            if (aData.length > max) {
                max = aData.length;
            }
        }
        double[] sumOfColumns = new double[max];
        for (double[] aData : data) {
            for (int c = 0; c < aData.length; c++) {
                sumOfColumns[c] += aData[c];
            }
        }

        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue((data[r][c] * 100) / sumOfColumns[c], rowKey, columnKey);
            }
        }
        return result;
    }

    private void createGraphInRootDirectory(CategoryDataset dataset, String graphTitleName, String fileName,
            String yAxisTitle, Set<String> modes) throws IOException {
        final boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitleName,
                "Iteration", yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modes);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
                GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    // csv for root modeChoice.png
    void writeToRootCSV() {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("modeChoice.csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = cumulativeModeChosenForModeChoice;

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            int max = modeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
                final StringBuilder builder = new StringBuilder(String.valueOf(iteration));
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

    //csv for reference mode choice
    public void writeToRootCSVForReference() {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("reference_modeChoice.csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = cumulativeModeChosenForReference;

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            double sum = benchMarkData.values().stream().reduce((x, y) -> x + y).orElse(0.0);
            StringBuilder builder = new StringBuilder("benchmark");
            for (String mode : modes) {
                if (benchMarkData.get(mode) != null) {
                    builder.append(",").append((benchMarkData.get(mode) * 100) / sum);
                } else {
                    builder.append(",0");
                }
            }
            out.write(builder.toString());
            out.newLine();
            int max = modeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            double[] sumInIteration = new double[max + 1];
            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            sumInIteration[iteration] += modeCount.get(mode);
                        }
                    }
                }
            }

            for (int iteration = 0; iteration <= max; iteration++) {
                Map<String, Integer> modeCount = modeChoiceInIteration.get(iteration);
                builder = new StringBuilder(iteration + "");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append(",").append((modeCount.get(mode) * 100) / sumInIteration[iteration]);
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

    private Map<String, Double> benchmarkCsvLoader(String path) {
        Map<String, Double> benchmarkData = new HashMap<>();

        try (FileReader fileReader = new FileReader(path)) {
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line1 = bufferedReader.readLine();
            String line2 = bufferedReader.readLine();
            String[] mode = line1.split(",");
            String[] share = line2.split(",");
            for (int i = 1; i < mode.length; i++) {
                benchmarkData.put(mode[i], Double.parseDouble(share[i]));
            }
        } catch (Exception ex) {
            log.warn("Unable to load benchmark CSV via path '{}'", path, ex);
        }
        return benchmarkData;
    }

}
