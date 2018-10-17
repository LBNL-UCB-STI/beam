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
import java.io.BufferedWriter;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;

public class ModeChosenStats implements IGraphStats, MetricsSupport {
    private static final String graphTitle = "Mode Choice Histogram";
    private static final String graphTitleBenchmark = "Reference Mode Choice Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "mode_choice";
    private Set<String> iterationTypeSet = new HashSet<>();
    private Map<Integer, Map<String, Integer>> modeChoiceInIteration = new HashMap<>();
    private Logger log = LoggerFactory.getLogger(this.getClass());

    private Set<String> modesChosen = new TreeSet<>();
    private Set<String> cumulativeModeChosenForModeChoice = new TreeSet();
    private Set<String> cumulativeModeChosenForReference = new TreeSet();
    private Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private final Map<String, Double> benchMarkData;
    private String benchmarkFileLoc ;


    private final IStatComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation;

    public static class ModeChosenComputation implements IStatComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> {

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
            int index = 0;
            for (int hour = 0; hour <= maxHour; hour++) {
                Map<String, Integer> hourData = stat.get(hour);
                if (hourData != null) {
                    modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
                } else {
                    modeOccurrencePerHour[index] = 0;
                }
                index = index + 1;
            }
            return modeOccurrencePerHour;
        }
    }

    public ModeChosenStats(IStatComputation<Tuple<Map<Integer, Map<String, Integer>>, Set<String>>, double[][]> statComputation , BeamConfig beamConfig) {
        benchmarkFileLoc = beamConfig.beam().calibration().mode().benchmarkFileLoc();
        this.statComputation = statComputation;
        benchMarkData = benchmarkCsvLoader(benchmarkFileLoc);
    }

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

        createModeChosenCSV(hourModeFrequency, event.getIteration());
        OutputDirectoryHierarchy outputDirectoryHierarchy = event.getServices().getControlerIO();
        String fileName = outputDirectoryHierarchy.getOutputFilename("modeChoice.png");
        CategoryDataset dataset = buildModeChoiceDatasetForGraph();
        if (dataset != null) {
            createRootModeChoosenGraph(dataset, graphTitle ,fileName, "# mode choosen",cumulativeModeChosenForModeChoice);
        }
        writeToRootCSV();

        fileName = outputDirectoryHierarchy.getOutputFilename("reference_modeChoice.png");
        cumulativeModeChosenForReference.addAll(benchMarkData.keySet());
        CategoryDataset referenceDataset = buildModeChoiceReferenceDatasetForGraph();
        if (referenceDataset != null) {
            createRootModeChoosenGraph(referenceDataset,graphTitleBenchmark, fileName, "# mode choosen(Percent)",cumulativeModeChosenForReference);
        }
        writeToRootCSVForReference();
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) {

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
    public void updateModeChoiceInIteration(Integer iteration) {
        Set<Integer> hours = hourModeFrequency.keySet();
        Map<String, Integer> totalModeChoice = new HashMap<>();
        for (Integer hour : hours) {
            Map<String, Integer> iterationHourData = hourModeFrequency.get(hour);
            Set<String> iterationModes = iterationHourData.keySet();
            for (String iterationMode : iterationModes) {
                Integer freq = iterationHourData.get(iterationMode);
                totalModeChoice.merge(iterationMode, freq, (a, b) -> b + a);

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

        String SEPERATOR = ",";

        CSVWriter csvWriter = new CSVWriter(GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".csv"));
        BufferedWriter bufferedWriter = csvWriter.getBufferedWriter();


        List<Integer> hours = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeChosen.keySet());
        List<String> modesFuelList = GraphsStatsAgentSimEventsListener.getSortedStringList(modesChosen);

        int maxHour = hours.get(hours.size() - 1);
        try {
            bufferedWriter.append("Modes");
            bufferedWriter.append(SEPERATOR);
            for (int j = 0; j <= maxHour; j++) {
                bufferedWriter.append("Bin_")
                        .append(String.valueOf(j))
                        .append(SEPERATOR);
            }
            bufferedWriter.append("\n");

            for (String modeChosen : modesFuelList) {

                bufferedWriter.append(modeChosen);
                bufferedWriter.append(SEPERATOR);

                for (int j = 0; j <= maxHour; j++) {
                    Map<String, Integer> modesData = hourModeChosen.get(j);


                    String modeHourValue = "0";

                    if (modesData != null) {
                        if (modesData.get(modeChosen) != null) {
                            modeHourValue = modesData.get(modeChosen).toString();
                        }
                    }

                    bufferedWriter.append(modeHourValue);
                    bufferedWriter.append(SEPERATOR);
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
    private CategoryDataset buildModeChoiceReferenceDatasetForGraph() throws IOException{
        CategoryDataset categoryDataset = null;
        double[][] dataset = statComputation.compute(new Tuple<>(modeChoiceInIteration, cumulativeModeChosenForReference));

        if (dataset != null) {
            categoryDataset = createReferenceCategoryDataset("it.", dataset);
        }
        return categoryDataset;
    }

    // The data is converted into average and compared with the data of benchmark.
    public CategoryDataset createReferenceCategoryDataset(String columnKeyPrefix, double[][] data) throws IOException{
        DefaultCategoryDataset result = new DefaultCategoryDataset();
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(benchMarkData.keySet());
        double sum = benchMarkData.values().stream().reduce((x,y) -> x + y).orElse(0.0);
        for(int i = 0 ; i< modesChosenList.size() ;i++ ){
            String rowKey = String.valueOf(i + 1);
            result.addValue((benchMarkData.get(modesChosenList.get(i)) * 100) / sum, rowKey , "benchmark");
        }
        int max = 0;
        for (int r = 0; r < data.length; r++) {
            if(data[r].length > max){
                max = data[r].length;
            }
        }
        double[] sumOfColumns = new double[max];
        for (int r = 0; r < data.length; r++) {
            for (int c = 0; c < data[r].length; c++) {
                sumOfColumns[c] += data[r][c];
            }
        }

        for (int r = 0; r < data.length; r++) {
            String rowKey = String.valueOf(r + 1);
            for (int c = 0; c < data[r].length; c++) {
                String columnKey = columnKeyPrefix + c;
                result.addValue((data[r][c] * 100 )/sumOfColumns[c] , rowKey, columnKey);
            }
        }
        return result;
    }


//    generating graph in root directory
    private void createRootModeChoosenGraph(CategoryDataset dataset,String graphTitleName, String fileName, String yAxisTitle, Set<String> modes) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitleName, "Iteration", yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>(modes);
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        GraphUtils.saveJFreeChartAsPNG(chart, fileName, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    // csv for root modeChoice.png
    public void writeToRootCSV() {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("modeChoice.csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = cumulativeModeChosenForModeChoice;

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

    //csv for reference mode choice
    public void writeToRootCSVForReference() {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename("reference_modeChoice.csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = cumulativeModeChosenForReference;

            String heading = modes.stream().reduce((x, y) -> x + "," + y).orElse("");
            out.write("iterations," + heading);
            out.newLine();

            double sum = benchMarkData.values().stream().reduce((x,y) -> x + y).orElse(0.0);
            StringBuilder builder = new StringBuilder("benchmark");
            for (String mode : modes) {
                if (benchMarkData.get(mode) != null) {
                    builder.append(",").append((benchMarkData.get(mode) * 100) / sum);
                }
                else {
                    builder.append(",0");
                }
            }
            out.write(builder.toString());
            out.newLine();
            int max = modeChoiceInIteration.keySet().stream().mapToInt(x -> x).max().orElse(0);

            double[] sumInIteration = new double[max+1];
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
                builder = new StringBuilder(iteration +"");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append(",").append((modeCount.get(mode) * 100)/ sumInIteration[iteration]);
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
        Map<String, Double> benchMarkData = new HashMap<>();

        try (FileReader fileReader = new FileReader(path)) {
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line1 = bufferedReader.readLine();
            String line2 = bufferedReader.readLine();
            String[] mode = line1.split(",");
            String[] share = line2.split(",");
            for (int i = 1; i < mode.length; i++) {
                benchMarkData.put(mode[i], Double.parseDouble(share[i]));
            }
        } catch (Exception ex) {
            log.warn("Unable to load benchmark CSV via path '{}'", path, ex);
        }
        return benchMarkData;
    }

}
